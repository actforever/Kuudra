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
    private final Set<Object> disabledComponents = Collections.newSetFromMap(new IdentityHashMap<>());
    private final SystemEventPublisher events;
    private final ContextCodec codec = ContextCodecs.defaultCodec();
    private final SessionCoordinator coordinator = new SessionCoordinator();
    private final SessionManager sessionManager;
    private final SessionManager.AtomicValueContext globalContext;
    private final int maxEventHops;
    private final Duration dispatcherPollInterval;
    private final long shutdownSessionDrainTimeoutMs;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean paused;
    private volatile CompletableFuture<Void> runtimeResumeSignal = CompletableFuture.completedFuture(null);
    private int activeExecutions;
    private Set<UUID> kernelPausedSessions = Set.of();

    public KuudraRuntime(int queueCapacity, int workerThreads) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, Map.of(), 256, SystemEventPublisher.noop()); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, 256, SystemEventPublisher.noop()); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals, int maxEventHops) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, maxEventHops, SystemEventPublisher.noop()); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals, int maxEventHops, SystemEventPublisher events) { this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, maxEventHops, events, 200, 5_000); }
    public KuudraRuntime(int queueCapacity, int workerThreads, Map<String,Object> globals, int maxEventHops,
                         SystemEventPublisher events, int dispatcherPollIntervalMs, int shutdownSessionDrainTimeoutMs) {
        this(new InMemoryKuudraTaskQueue(queueCapacity), workerThreads, globals, maxEventHops, events,
                dispatcherPollIntervalMs, shutdownSessionDrainTimeoutMs);
    }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads) { this(queue, workerThreads, Map.of(), 256, SystemEventPublisher.noop()); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals) { this(queue, workerThreads, globals, 256, SystemEventPublisher.noop()); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals, int maxEventHops) { this(queue, workerThreads, globals, maxEventHops, SystemEventPublisher.noop()); }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals, int maxEventHops, SystemEventPublisher events) {
        this(queue, workerThreads, globals, maxEventHops, events, 200, 5_000);
    }
    public KuudraRuntime(KuudraTaskQueue queue, int workerThreads, Map<String,Object> globals, int maxEventHops,
                         SystemEventPublisher events, int dispatcherPollIntervalMs, int shutdownSessionDrainTimeoutMs) {
        this.queue = Objects.requireNonNull(queue); this.workers = Executors.newFixedThreadPool(workerThreads);
        this.events = Objects.requireNonNull(events, "events");
        if (maxEventHops < 1) throw new KuudraException("maxEventHops must be positive"); this.maxEventHops = maxEventHops;
        if (dispatcherPollIntervalMs < 1 || shutdownSessionDrainTimeoutMs < 0) throw new KuudraException("Runtime timing settings are invalid");
        this.dispatcherPollInterval = Duration.ofMillis(dispatcherPollIntervalMs);
        this.shutdownSessionDrainTimeoutMs = shutdownSessionDrainTimeoutMs;
        this.globalContext = new SessionManager.AtomicValueContext(codec, globals);
        this.sessionManager = new SessionManager(workers, codec, this::sessionTerminal);
        this.dispatcher = new Thread(this::dispatch, "kuudra-runtime-dispatcher"); this.dispatcher.start();
    }

    public GlobalContext globalContext() { return globalContext; }
    public SessionManager sessions() { return sessionManager; }
    public SessionCoordinator coordinator() { return coordinator; }
    public FlowContext flowContext(String flowId) { synchronized (monitor) { return requireFlow(flowId).context; } }
    public int queuedTasks() { return queue.size(); }

    /** App reconciliation gate for a component instance already bound into one or more Flows. */
    public void setComponentEnabled(Object component, boolean enabled) {
        synchronized (monitor) {
            if (enabled) disabledComponents.remove(component);
            else disabledComponents.add(component);
        }
    }

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
    public RuntimeCheckpoint pause() {
        synchronized (monitor) {
            if (closed.get()) throw new KuudraException("Runtime is closed");
            if (paused) return checkpoint();
            runtimeResumeSignal = new CompletableFuture<>();
            paused = true;
            while (activeExecutions > 0 && !closed.get()) {
                try { monitor.wait(); }
                catch (InterruptedException interrupted) {
                    paused = false;
                    runtimeResumeSignal.complete(null);
                    monitor.notifyAll();
                    Thread.currentThread().interrupt();
                    throw new KuudraException("Interrupted while pausing Runtime", interrupted);
                }
            }
            if (closed.get()) throw new KuudraException("Runtime was closed while pausing");
        }
        try {
            pauseComponents();
            kernelPausedSessions = sessionManager.pauseAllActive();
        } catch (RuntimeException failure) {
            try { resumeComponents(); } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            synchronized (monitor) {
                paused=false;
                runtimeResumeSignal.complete(null);
                monitor.notifyAll();
            }
            throw KuudraException.wrap("Failed to pause Runtime components", failure);
        }
        RuntimeCheckpoint checkpoint = checkpoint();
        event("runtime.paused", Map.of("sessions", checkpoint.sessions().size(), "queuedTasks", checkpoint.queuedTasks()));
        return checkpoint;
    }
    public void resume() {
        synchronized (monitor) { if (closed.get()) throw new KuudraException("Runtime is closed"); }
        resumeComponents();
        sessionManager.resumeAll(kernelPausedSessions);
        kernelPausedSessions = Set.of();
        synchronized (monitor) {
            paused = false;
            runtimeResumeSignal.complete(null);
            monitor.notifyAll();
        }
        event("runtime.resumed", Map.of());
    }
    public boolean paused() { return paused; }

    public RuntimeCheckpoint checkpoint() {
        synchronized (monitor) {
            if (!paused || activeExecutions != 0) throw new KuudraException("Runtime checkpoint requires a quiescent pause barrier");
            Map<String,Map<String,Object>> flowContexts=new LinkedHashMap<>();
            flows.forEach((id,flow)->flowContexts.put(id,flow.context.snapshot()));
            return new RuntimeCheckpoint(java.time.Instant.now(),queue.size(),flows(),sessionManager.snapshots(),
                    globalContext.snapshot(),flowContexts);
        }
    }

    private void pauseComponents() {
        for (ManagedSource source : List.copyOf(sources)) if (componentEnabled(source.source())) pauseLifecycle(source.source());
        for (Lifecycle lifecycle : List.copyOf(componentLifecycles)) if (componentEnabled(lifecycle)) pauseLifecycle(lifecycle);
    }
    private void resumeComponents() {
        for (Lifecycle lifecycle : List.copyOf(componentLifecycles)) if (componentEnabled(lifecycle)) resumeLifecycle(lifecycle);
        for (ManagedSource source : List.copyOf(sources)) if (componentEnabled(source.source())) resumeLifecycle(source.source());
    }
    private boolean componentEnabled(Object component) { synchronized (monitor) { return !disabledComponents.contains(component); } }
    private static void pauseLifecycle(Lifecycle lifecycle) {
        if (lifecycle instanceof PausableLifecycle pausable) pausable.pause().toCompletableFuture().join();
    }
    private static void resumeLifecycle(Lifecycle lifecycle) {
        if (lifecycle instanceof PausableLifecycle pausable) pausable.resume().toCompletableFuture().join();
    }

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
    public boolean pauseSession(UUID id) { boolean result=sessionManager.pause(id); if(result) event("session.paused",Map.of("sessionId",id.toString())); return result; }
    public boolean resumeSession(UUID id) { boolean result=sessionManager.resume(id); if(result) event("session.resumed",Map.of("sessionId",id.toString())); return result; }
    @Override public boolean hasActiveSession(String flowId,String groupKey) { return activeSessionCount(flowId,groupKey)>0; }
    @Override public int activeSessionCount(String flowId,String groupKey) { return (int)sessionManager.snapshots().stream().filter(s->s.flowId().equals(flowId)&&s.groupKey().equals(groupKey)&&active(s.status())).count(); }
    @Override public Optional<SessionSnapshot> session(UUID id) { return sessionManager.snapshot(id); }
    @Override public Optional<FlowSnapshot> flow(String id) { synchronized(monitor){ RegisteredFlow f=flows.get(id); return f==null?Optional.empty():Optional.of(new FlowSnapshot(id,sessionManager.activeCount(id),queue.size())); } }
    public List<FlowSnapshot> flows(){ synchronized(monitor){return flows.keySet().stream().map(this::flow).flatMap(Optional::stream).toList();} }
    public boolean awaitNoActiveSessions(Duration timeout)throws InterruptedException { sessionManager.awaitDrained(timeout.toMillis()); return sessionManager.snapshots().stream().noneMatch(s->active(s.status())); }

    private RegisteredFlow registeredFlow(String id){ synchronized(monitor){return requireFlow(id);} }
    private RegisteredFlow requireFlow(String id){RegisteredFlow flow=flows.get(id);if(flow==null)throw new KuudraException("Unknown Flow: "+id);return flow;}
    private boolean enqueue(RegisteredFlow flow,String nodeId,KuudraEventWrapper wrapper){
        if(closed.get()||(paused&&wrapper instanceof RawEventWrapper))return false;
        FlowNode node=flow.flow.node(nodeId); if(node.inputDomain()!=wrapper.domain()) throw new KuudraException("Event domain mismatch at "+nodeId);
        if(wrapper.event().lineage().hops()>=maxEventHops){event("event.rejected.max-hops",Map.of("flowId",flow.flow.id(),"nodeId",nodeId,"maxEventHops",maxEventHops));return false;}
        SessionManager.ManagedSession owner=null;
        if(wrapper instanceof SessionEventWrapper session){owner=sessionManager.require(session.session().id());if(owner==null||!sessionManager.acquire(owner))return false;}
        boolean offered=queue.offer(new RuntimeTask.EventTask(flow.flow.id(),flow.flow.revision(),nodeId,wrapper));
        if (offered) debugEvent("runtime.event.enqueued", Map.of("flowId", flow.flow.id(), "revision", flow.flow.revision(),
                "nodeId", nodeId, "domain", wrapper.domain().name(), "eventId", wrapper.event().id().toString(), "queuedTasks", queue.size()));
        else debugEvent("runtime.event.queue-full", Map.of("flowId", flow.flow.id(), "nodeId", nodeId,
                "eventId", wrapper.event().id().toString(), "queuedTasks", queue.size()));
        if(!offered&&owner!=null)sessionManager.release(owner,null); return offered;
    }
    private void dispatch(){ while(!closed.get()){ try{ Optional<RuntimeTask> next=queue.poll(dispatcherPollInterval);if(next.isEmpty())continue;if(next.get() instanceof RuntimeTask.StopTask)return;RuntimeTask.EventTask task=(RuntimeTask.EventTask)next.get();debugEvent("runtime.event.dispatched",taskData(task));RegisteredFlow flow=registeredFlow(task.flowId());if(task.wrapper() instanceof SessionEventWrapper sw){SessionManager.ManagedSession s=sessionManager.require(sw.session().id());if(s==null){debugEvent("runtime.event.session-missing",taskData(task));continue;}s.submit(()->execute(flow,task,s));}else workers.execute(()->execute(flow,task,null)); }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(RuntimeException e){event("runtime.dispatch.failed",Map.of("error",e.toString()));}} }
    private void execute(RegisteredFlow flow,RuntimeTask.EventTask task,SessionManager.ManagedSession session){
        Throwable failure=null; boolean releaseHere=true; boolean asynchronous=false; boolean entered=false;
        try{
            enterExecution();
            entered=true;
            debugEvent("runtime.node.execution.started", taskData(task));
            if (session != null) session.awaitResumed();
            if(flow.flow.revision()!=task.flowRevision()||(session!=null&&(!session.active()||session.cancelled.get()||session.failure.get()!=null)))return;
            FlowNode node=flow.flow.node(task.nodeId()); KuudraEvent input=task.wrapper().event();
            Object component = node instanceof FlowNode.InterpreterNode interpreter ? interpreter.interpreter()
                    : node instanceof FlowNode.HandlerNode handler ? handler.handler()
                    : node instanceof FlowNode.AdapterNode adapter ? adapter.adapter()
                    : node instanceof FlowNode.IngressNode ingress ? ingress.ingress()
                    : node instanceof FlowNode.EgressNode egress ? egress.egress() : null;
            synchronized (monitor) { if (component != null && disabledComponents.contains(component)) return; }
            EventContext context=context(flow,session,node.id(),input);
            if(node instanceof FlowNode.IngressNode ingress){ executeIngress(flow,ingress,input,context); return; }
            if(node instanceof FlowNode.HandlerNode handler){ releaseHere=false; asynchronous=true; executeHandler(flow,handler,input,context,session); return; }
            List<KuudraEvent> output;
            if(node instanceof FlowNode.AdapterNode adapter)output=adapter.adapter().adapt(input,context);
            else if(node instanceof FlowNode.InterpreterNode interpreter)output=interpreter.interpreter().interpret(input,context);
            else output=((FlowNode.EgressNode)node).egress().export(input,context);
            route(flow,node,task.wrapper(),normalize(input,output,node instanceof FlowNode.EgressNode,session));
        }catch(Throwable e){failure=e;event("event.execution.failed",Map.of("flowId",flow.flow.id(),"nodeId",task.nodeId(),"error",e.toString()));}
        finally{if(session!=null&&releaseHere)sessionManager.release(session,failure);if(entered&&!asynchronous){debugEvent("runtime.node.execution.completed",completionData(task,failure));exitExecution();}}
    }
    private void executeHandler(RegisteredFlow flow,FlowNode.HandlerNode node,KuudraEvent input,EventContext context,SessionManager.ManagedSession session){
        AtomicBoolean open=new AtomicBoolean(true);
        EventEmitter emitter=output->{if(!open.get())throw new KuudraException("EventHandler emitted after CompletionStage completion");return routeOne(flow,node,new SessionEventWrapper(input,session.reference()),derive(input,output,false,session));};
        ActionContext action=new ActionContext(session.id,flow.flow.id(),session.context.snapshot(),session.context,flow.context.snapshot(),flow.context,controlToken(session),emitter,globalContext.snapshot(),globalContext,context.configuration());
        try{node.handler().handle(input,action).whenComplete((v,error)->{open.set(false);sessionManager.release(session,error);debugEvent("runtime.node.execution.completed",Map.of("flowId",flow.flow.id(),"nodeId",node.id(),"eventId",input.id().toString(),"outcome",error==null?"success":"failed"));exitExecution();if(error==null)event("event-handler.completed",Map.of("sessionId",session.id.toString(),"handlerId",node.id()));});}
        catch(Throwable error){open.set(false);sessionManager.release(session,error);debugEvent("runtime.node.execution.completed",Map.of("flowId",flow.flow.id(),"nodeId",node.id(),"eventId",input.id().toString(),"outcome","failed"));exitExecution();}
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
        EventContext base=new EventContext(flow.flow.id(),reference,sessionValues,session==null?null:session.context,flow.context.snapshot(),flow.context,session==null?()->false:controlToken(session),globalContext.snapshot(),globalContext,Map.of());
        Map<String,Object> configuration=flow.configuration.get(nodeId).resolve(event,base);
        return new EventContext(base.flowId(),base.session(),base.sessionValues(),base.sessionContext(),base.flowValues(),base.flowContext(),base.cancellationToken(),base.globalValues(),base.globalContext(),configuration);
    }
    private void sessionTerminal(SessionManager.ManagedSession session){SessionCoordinator.Group group=findGroup(session);coordinator.terminal(group,session.id);event("session."+session.status.name().toLowerCase(),Map.of("sessionId",session.id.toString(),"groupKey",session.groupKey));}
    private CancellationToken controlToken(SessionManager.ManagedSession session){return new CancellationToken(){public boolean isCancellationRequested(){return session.cancelled.get();}public boolean isPauseRequested(){return paused||session.paused;}public CompletionStage<Void> awaitResumed(){CompletableFuture<Void> kernelSignal=runtimeResumeSignal;return kernelSignal.thenCompose(ignored->session.resumed());}};}
    private SessionCoordinator.Group findGroup(SessionManager.ManagedSession session){RegisteredFlow flow=registeredFlow(session.flowId);FlowNode.IngressNode ingress=(FlowNode.IngressNode)flow.flow.node(session.ingressId);String scope=ingress.scheduling().groupScope()==SessionGroupScope.INGRESS?ingress.instanceId():flow.flow.id()+"@"+session.revision;return new SessionCoordinator.Group(scope,ingress.instanceId(),session.groupKey);}
    private void enterExecution() throws InterruptedException { synchronized (monitor) { while (paused && !closed.get()) monitor.wait(); if(closed.get())throw new InterruptedException("Runtime closed");activeExecutions++; } }
    private void exitExecution() { synchronized (monitor) { activeExecutions--;if(activeExecutions<0)throw new KuudraException("Runtime execution counter underflow");if(activeExecutions==0)monitor.notifyAll(); } }
    private void event(String type,Map<String,Object> data){events.publish(SystemEvent.of(type,data));}
    private void debugEvent(String type,Map<String,Object> data){events.publish(SystemEvent.debug(type,data));}
    private Map<String,Object> taskData(RuntimeTask.EventTask task){return Map.of("flowId",task.flowId(),"revision",task.flowRevision(),"nodeId",task.nodeId(),"domain",task.wrapper().domain().name(),"eventId",task.wrapper().event().id().toString(),"queuedTasks",queue.size());}
    private Map<String,Object> completionData(RuntimeTask.EventTask task,Throwable failure){return Map.of("flowId",task.flowId(),"nodeId",task.nodeId(),"eventId",task.wrapper().event().id().toString(),"outcome",failure==null?"success":"failed");}
    private static boolean active(SessionStatus s){return s==SessionStatus.ACTIVE||s==SessionStatus.PAUSED||s==SessionStatus.CANCELLATION_REQUESTED;}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;synchronized(monitor){paused=false;runtimeResumeSignal.complete(null);monitor.notifyAll();}List<ManagedSource> copy; synchronized(monitor){copy=List.copyOf(sources);}copy.forEach(s->unregister(s).toCompletableFuture().join());sessionManager.cancelAll();try{sessionManager.awaitDrained(shutdownSessionDrainTimeoutMs);}catch(InterruptedException e){Thread.currentThread().interrupt();}queue.offer(new RuntimeTask.StopTask());queue.close();dispatcher.interrupt();List<Lifecycle> lifecycles=new ArrayList<>(componentLifecycles);for(int index=lifecycles.size()-1;index>=0;index--)try{lifecycles.get(index).stop().toCompletableFuture().join();}catch(RuntimeException ignored){}componentLifecycles.clear();synchronized(monitor){disabledComponents.clear();}workers.shutdownNow();}
    private record ManagedSource(EventSource source,List<SourceTarget> targets,AtomicBoolean closed){ManagedSource(EventSource source,List<SourceTarget>targets){this(source,targets,new AtomicBoolean());}}
    private static final class RegisteredFlow{
        final KuudraFlow flow; final SessionManager.AtomicValueContext context;
        final Map<String,PlaceholderResolver.CompiledMap> configuration;
        RegisteredFlow(KuudraFlow flow,SessionManager.AtomicValueContext context){
            this.flow=flow;this.context=context;
            Map<String,PlaceholderResolver.CompiledMap> compiled=new LinkedHashMap<>();
            flow.nodes().forEach((id,node)->compiled.put(id,PlaceholderResolver.compileMap(node.configuration(),node.inputDomain())));
            this.configuration=Map.copyOf(compiled);
        }
    }
}
