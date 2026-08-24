package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Dual-domain Runtime: RAW routing, Ingress admission, SESSION routing and explicit Egress. */
public final class KuudraRuntime implements RuntimeStateView, AutoCloseable {
    private final Object monitor = new Object();
    private final KuudraTaskQueue queue;
    private final ExecutorService workers;
    private final Thread dispatcher;
    private final Map<String, RegisteredFlow> flows = new LinkedHashMap<>();
    private final List<ManagedSource> sources = new ArrayList<>();
    private final Set<Lifecycle> componentLifecycles = Collections.newSetFromMap(new IdentityHashMap<>());
    private final SimpleSystemEventBus events = new SimpleSystemEventBus();
    private final ContextCodec codec = ContextCodecs.defaultCodec();
    private final SessionCoordinator coordinator = new SessionCoordinator();
    private final SessionManager sessionManager;
    private final SessionManager.AtomicValueContext globalContext;
    private final int maxEventHops;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KuudraRuntime(int queueCapacity, int workerThreads) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, Map.of(), 256); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, 256); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals, int maxEventHops) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, maxEventHops); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads) { this(queue, workerThreads, Map.of(), 256); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals) { this(queue, workerThreads, globals, 256); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals, int maxEventHops) {
        this.queue = Objects.requireNonNull(queue); this.workers = Executors.newFixedThreadPool(workerThreads);
        if (maxEventHops < 1) throw new KuudraException("maxEventHops must be positive"); this.maxEventHops = maxEventHops;
        this.globalContext = new SessionManager.AtomicValueContext(codec, globals);
        this.sessionManager = new SessionManager(workers, codec, this::sessionTerminal);
        this.dispatcher = new Thread(this::dispatch, "kuudra-runtime-dispatcher"); this.dispatcher.start();
    }

    public SystemEventBus systemEvents() { return events; }
    public GlobalContext globalContext() { return globalContext; }
    public SessionManager sessions() { return sessionManager; }
    public SessionCoordinator coordinator() { return coordinator; }
    public FlowContext flowContext(String flowId) { synchronized (monitor) { return requireFlow(flowId).context; } }
    public int queuedTasks() { return queue.size(); }

    public void registerFlow(KuudraFlow flow) {
        RegisteredFlow registered;
        try { registered = new RegisteredFlow(flow, new SessionManager.AtomicValueContext(codec, Map.of())); }
        catch (RuntimeException error) { throw KuudraException.wrap("Failed to compile Flow " + flow.id(), error); }
        synchronized (monitor) { if (flows.putIfAbsent(flow.id(), registered) != null) throw new KuudraException("Flow already registered: " + flow.id()); }
        List<Lifecycle> started = new ArrayList<>();
        try {
            for (FlowNode node : flow.nodes().values()) {
                Lifecycle lifecycle = node instanceof FlowNode.InterpreterNode interpreter ? interpreter.interpreter()
                        : node instanceof FlowNode.HandlerNode handler ? handler.handler() : null;
                if (lifecycle != null && componentLifecycles.add(lifecycle)) {
                    try { lifecycle.start().toCompletableFuture().join(); started.add(lifecycle); }
                    catch (RuntimeException error) { componentLifecycles.remove(lifecycle); throw KuudraException.wrap("Failed to start component " + node.id(), error); }
                }
            }
        } catch (RuntimeException error) {
            for (int index = started.size() - 1; index >= 0; index--) try { started.get(index).stop().toCompletableFuture().join(); } catch (RuntimeException closeError) { error.addSuppressed(closeError); }
            componentLifecycles.removeAll(started); synchronized (monitor) { flows.remove(flow.id(), registered); } throw error;
        }
        event("flow.registered", Map.of("flowId", flow.id(), "revision", flow.revision()));
    }
    public void activateFlow(String id) { status(id, FlowStatus.ACTIVE, "flow.active"); }
    public void pauseFlow(String id) { status(id, FlowStatus.PAUSED, "flow.paused"); }
    public void resumeFlow(String id) { activateFlow(id); }
    public void stopFlow(String id) {
        RegisteredFlow flow; synchronized (monitor) { flow=requireFlow(id); flow.status=FlowStatus.STOPPING; }
        sessionManager.snapshots().stream().filter(s -> s.flowId().equals(id) && active(s.status())).forEach(s -> cancel(s.id()));
        markStoppedIfDrained(id); event("flow.stopping", Map.of("flowId", id));
    }
    private void status(String id, FlowStatus status, String type) { synchronized (monitor) { requireFlow(id).status=status; } event(type, Map.of("flowId", id)); }

    public CompletionStage<SourceRegistration> registerSource(String flowId, String target, EventSource source) {
        return registerSource(List.of(new SourceTarget(flowId,target)), source);
    }
    public CompletionStage<SourceRegistration> registerSource(List<SourceTarget> targets, EventSource source) {
        Objects.requireNonNull(source); List<SourceTarget> copy=List.copyOf(targets);
        for (SourceTarget target:copy) {
            RegisteredFlow flow=registeredFlow(target.flowId); FlowNode node=flow.flow.node(target.targetNodeId);
            if (node.inputDomain()!=EventDomain.RAW) throw new KuudraException("EventSource target must be RAW: "+target);
        }
        ManagedSource managed=new ManagedSource(source,copy); synchronized(monitor){sources.add(managed);}
        source.setEmitter(event -> copy.stream().mapToInt(t -> publish(t.flowId,t.targetNodeId,event)?1:0).sum()>0);
        return source.start().thenApply(ignored -> { event("event-source.started",Map.of("targets",copy.size())); return (SourceRegistration) () -> unregister(managed); })
                .exceptionallyCompose(error -> {
                    synchronized (monitor) { sources.remove(managed); } managed.closed.set(true);
                    return source.stop().handle((ignored, stopError) -> {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        KuudraException failure = KuudraException.wrap("Failed to start EventSource", cause);
                        if (stopError != null) failure.addSuppressed(stopError); throw failure;
                    });
                });
    }
    private CompletionStage<Void> unregister(ManagedSource source) {
        if (!source.closed.compareAndSet(false,true)) return CompletableFuture.completedFuture(null);
        synchronized(monitor){sources.remove(source);} return source.source.stop();
    }
    public record SourceTarget(String flowId,String targetNodeId) { public SourceTarget { if(flowId==null||flowId.isBlank()||targetNodeId==null||targetNodeId.isBlank()) throw new IllegalArgumentException("source target must not be blank"); } }

    public boolean publish(String flowId,String target,KuudraEvent event) { return enqueue(registeredFlow(flowId),target,new RawEventWrapper(event)); }
    public boolean cancel(UUID id) { boolean result=sessionManager.cancel(id); if(result) event("session.cancel.requested",Map.of("sessionId",id.toString())); return result; }
    @Override public boolean hasActiveSession(String flowId,String groupKey) { return activeSessionCount(flowId,groupKey)>0; }
    @Override public int activeSessionCount(String flowId,String groupKey) { return (int)sessionManager.snapshots().stream().filter(s->s.flowId().equals(flowId)&&s.groupKey().equals(groupKey)&&active(s.status())).count(); }
    @Override public Optional<SessionSnapshot> session(UUID id) { return sessionManager.snapshot(id); }
    @Override public Optional<FlowSnapshot> flow(String id) { synchronized(monitor){ RegisteredFlow f=flows.get(id); return f==null?Optional.empty():Optional.of(new FlowSnapshot(id,f.status,sessionManager.activeCount(id),queue.size())); } }
    public List<FlowSnapshot> flows(){ synchronized(monitor){return flows.keySet().stream().map(this::flow).flatMap(Optional::stream).toList();} }
    public boolean awaitNoActiveSessions(Duration timeout)throws InterruptedException { sessionManager.awaitDrained(timeout.toMillis()); return sessionManager.snapshots().stream().noneMatch(s->active(s.status())); }

    private RegisteredFlow registeredFlow(String id){ synchronized(monitor){return requireFlow(id);} }
    private RegisteredFlow requireFlow(String id){RegisteredFlow flow=flows.get(id);if(flow==null)throw new KuudraException("Unknown Flow: "+id);return flow;}
    private boolean enqueue(RegisteredFlow flow,String nodeId,KuudraEventWrapper wrapper){
        if(closed.get()||flow.status!=FlowStatus.ACTIVE)return false;
        FlowNode node=flow.flow.node(nodeId); if(node.inputDomain()!=wrapper.domain()) throw new KuudraException("Event domain mismatch at "+nodeId);
        if(wrapper.event().lineage().hops()>=maxEventHops){event("event.rejected.max-hops",Map.of("flowId",flow.flow.id(),"nodeId",nodeId,"maxEventHops",maxEventHops));return false;}
        SessionManager.ManagedSession owner=null;
        if(wrapper instanceof SessionEventWrapper session){owner=sessionManager.require(session.session().id());if(owner==null||!sessionManager.acquire(owner))return false;}
        boolean offered=queue.offer(new RuntimeTask.EventTask(flow.flow.id(),flow.flow.revision(),nodeId,wrapper));
        if(!offered&&owner!=null)sessionManager.release(owner,null); return offered;
    }
    private void dispatch(){ while(!closed.get()){ try{ Optional<RuntimeTask> next=queue.poll(Duration.ofMillis(200));if(next.isEmpty())continue;if(next.get() instanceof RuntimeTask.StopTask)return;RuntimeTask.EventTask task=(RuntimeTask.EventTask)next.get();RegisteredFlow flow=registeredFlow(task.flowId());if(task.wrapper() instanceof SessionEventWrapper sw){SessionManager.ManagedSession s=sessionManager.require(sw.session().id());if(s==null){continue;}s.submit(()->execute(flow,task,s));}else workers.execute(()->execute(flow,task,null)); }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(RuntimeException e){event("runtime.dispatch.failed",Map.of("error",e.toString()));}} }
    private void execute(RegisteredFlow flow,RuntimeTask.EventTask task,SessionManager.ManagedSession session){
        Throwable failure=null; boolean releaseHere=true;
        try{
            if(flow.status!=FlowStatus.ACTIVE||flow.flow.revision()!=task.flowRevision()||(session!=null&&(!session.active()||session.cancelled.get()||session.failure.get()!=null)))return;
            FlowNode node=flow.flow.node(task.nodeId()); KuudraEvent input=task.wrapper().event();
            EventContext context=context(flow,session,node.id(),input);
            if(node instanceof FlowNode.IngressNode ingress){ executeIngress(flow,ingress,input,context); return; }
            if(node instanceof FlowNode.HandlerNode handler){ releaseHere=false; executeHandler(flow,handler,input,context,session); return; }
            List<KuudraEvent> output;
            if(node instanceof FlowNode.AdapterNode adapter)output=adapter.adapter().adapt(input,context);
            else if(node instanceof FlowNode.InterpreterNode interpreter)output=interpreter.interpreter().interpret(input,context);
            else output=((FlowNode.EgressNode)node).egress().export(input,context);
            route(flow,node,task.wrapper(),normalize(input,output,node instanceof FlowNode.EgressNode,session));
        }catch(Throwable e){failure=e;event("event.execution.failed",Map.of("flowId",flow.flow.id(),"nodeId",task.nodeId(),"error",e.toString()));}
        finally{if(session!=null&&releaseHere)sessionManager.release(session,failure);}
    }
    private void executeHandler(RegisteredFlow flow,FlowNode.HandlerNode node,KuudraEvent input,EventContext context,SessionManager.ManagedSession session){
        AtomicBoolean open=new AtomicBoolean(true);
        EventEmitter emitter=output->{if(!open.get())throw new KuudraException("EventHandler emitted after CompletionStage completion");return routeOne(flow,node,new SessionEventWrapper(input,session.reference()),derive(input,output,false,session));};
        ActionContext action=new ActionContext(session.id,flow.flow.id(),session.context.snapshot(),session.context,flow.context.snapshot(),flow.context,session.cancelled::get,emitter,globalContext.snapshot(),globalContext,context.configuration());
        try{node.handler().handle(input,action).whenComplete((v,error)->{open.set(false);sessionManager.release(session,error);if(error==null)event("event-handler.completed",Map.of("sessionId",session.id.toString(),"handlerId",node.id()));});}
        catch(Throwable error){open.set(false);sessionManager.release(session,error);}
    }
    private void executeIngress(RegisteredFlow flow,FlowNode.IngressNode node,KuudraEvent input,EventContext context){
        IngressDecision decision=node.ingress().admit(input,context);if(decision instanceof IngressDecision.Rejected rejected){event("ingress.rejected",Map.of("ingressId",node.id(),"reason",rejected.reason()));return;}
        IngressDecision.Accepted accepted=(IngressDecision.Accepted)decision;
        String scope=node.scheduling().groupScope()==SessionGroupScope.INGRESS?node.instanceId():flow.flow.id()+"@"+flow.flow.revision();
        SessionCoordinator.Group group=new SessionCoordinator.Group(scope,node.instanceId(),accepted.groupKey());
        Runnable launch=()->{
            SessionManager.ManagedSession session=sessionManager.create(flow.flow.id(),flow.flow.revision(),node.id(),accepted.groupKey(),accepted.initialSessionContext());
            coordinator.activated(group,session.id);event("session.active",Map.of("sessionId",session.id.toString(),"groupKey",accepted.groupKey(),"ingressId",node.id()));
            SessionEventWrapper wrapper=new SessionEventWrapper(derive(input,accepted.event(),false,null),session.reference());
            for(String next:flow.flow.next(node.id()))enqueue(flow,next,wrapper);sessionManager.completeIfIdle(session);
        };
        boolean admitted=coordinator.admit(group,node.scheduling(),launch,this::cancel);if(!admitted)event("ingress.deferred-or-dropped",Map.of("ingressId",node.id(),"groupKey",accepted.groupKey(),"policy",node.scheduling().policy().name()));
    }
    private void route(RegisteredFlow flow,FlowNode node,KuudraEventWrapper input,List<KuudraEvent> outputs){for(KuudraEvent output:outputs)routeOne(flow,node,input,output);}
    private boolean routeOne(RegisteredFlow flow,FlowNode node,KuudraEventWrapper input,KuudraEvent output){
        boolean any=false;for(String next:flow.flow.next(node.id())){KuudraEventWrapper wrapper=node instanceof FlowNode.EgressNode?new RawEventWrapper(output):input instanceof SessionEventWrapper sw?new SessionEventWrapper(output,sw.session()):new RawEventWrapper(output);any|=enqueue(flow,next,wrapper);}return any;
    }
    private List<KuudraEvent> normalize(KuudraEvent input,List<KuudraEvent> output,boolean egress,SessionManager.ManagedSession session){if(output==null)return List.of();return output.stream().map(e->derive(input,e,egress,session)).toList();}
    private KuudraEvent derive(KuudraEvent input,KuudraEvent output,boolean egress,SessionManager.ManagedSession session){if(output==null)throw new KuudraException("Component emitted null");EventLineage lineage=output.lineage().hops()>input.lineage().hops()?output.lineage():(egress&&session!=null?input.lineage().descendFrom(input,session.reference()):input.lineage().descendFrom(input));return output.withLineage(lineage);}
    private EventContext context(RegisteredFlow flow,SessionManager.ManagedSession session,String nodeId,KuudraEvent event){
        Map<String,Object> sessionValues=session==null?Map.of():session.context.snapshot();SessionReference reference=session==null?null:session.reference();
        EventContext base=new EventContext(flow.flow.id(),reference,sessionValues,session==null?null:session.context,flow.context.snapshot(),flow.context,session==null?()->false:session.cancelled::get,globalContext.snapshot(),globalContext,Map.of());
        Map<String,Object> configuration=flow.configuration.get(nodeId).resolve(event,base);
        return new EventContext(base.flowId(),base.session(),base.sessionValues(),base.sessionContext(),base.flowValues(),base.flowContext(),base.cancellationToken(),base.globalValues(),base.globalContext(),configuration);
    }
    private void sessionTerminal(SessionManager.ManagedSession session){SessionCoordinator.Group group=findGroup(session);coordinator.terminal(group,session.id);event("session."+session.status.name().toLowerCase(),Map.of("sessionId",session.id.toString(),"groupKey",session.groupKey));markStoppedIfDrained(session.flowId);}
    private SessionCoordinator.Group findGroup(SessionManager.ManagedSession session){RegisteredFlow flow=registeredFlow(session.flowId);FlowNode.IngressNode ingress=(FlowNode.IngressNode)flow.flow.node(session.ingressId);String scope=ingress.scheduling().groupScope()==SessionGroupScope.INGRESS?ingress.instanceId():flow.flow.id()+"@"+session.revision;return new SessionCoordinator.Group(scope,ingress.instanceId(),session.groupKey);}
    private void markStoppedIfDrained(String id){synchronized(monitor){RegisteredFlow f=flows.get(id);if(f!=null&&f.status==FlowStatus.STOPPING&&sessionManager.activeCount(id)==0)f.status=FlowStatus.STOPPED;}}
    private void event(String type,Map<String,Object> data){events.publish(SystemEvent.of(type,data));}
    private static boolean active(SessionStatus s){return s==SessionStatus.ACTIVE||s==SessionStatus.CANCELLATION_REQUESTED;}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;List<ManagedSource> copy; synchronized(monitor){copy=List.copyOf(sources);}copy.forEach(s->unregister(s).toCompletableFuture().join());sessionManager.cancelAll();try{sessionManager.awaitDrained(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}queue.offer(new RuntimeTask.StopTask());queue.close();dispatcher.interrupt();List<Lifecycle> lifecycles=new ArrayList<>(componentLifecycles);for(int index=lifecycles.size()-1;index>=0;index--)try{lifecycles.get(index).stop().toCompletableFuture().join();}catch(RuntimeException ignored){}componentLifecycles.clear();workers.shutdownNow();}
    private record ManagedSource(EventSource source,List<SourceTarget> targets,AtomicBoolean closed){ManagedSource(EventSource source,List<SourceTarget>targets){this(source,targets,new AtomicBoolean());}}
    private static final class RegisteredFlow{
        final KuudraFlow flow; final SessionManager.AtomicValueContext context;
        final Map<String,PlaceholderResolver.CompiledMap> configuration;
        volatile FlowStatus status=FlowStatus.ACTIVE;
        RegisteredFlow(KuudraFlow flow,SessionManager.AtomicValueContext context){
            this.flow=flow;this.context=context;
            Map<String,PlaceholderResolver.CompiledMap> compiled=new LinkedHashMap<>();
            flow.nodes().forEach((id,node)->compiled.put(id,PlaceholderResolver.compileMap(node.configuration(),node.inputDomain())));
            this.configuration=Map.copyOf(compiled);
        }
    }
}
