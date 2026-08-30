package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.event.EventDomain;
import io.github.actforever.kuudra.api.component.SourceRegistration;
import io.github.actforever.kuudra.api.runtime.AbilityExecutionClass;
import io.github.actforever.kuudra.api.system.SystemEvent;
import io.github.actforever.kuudra.api.system.SystemEventPublisher;
import io.github.actforever.kuudra.config.KuudraConfig;
import io.github.actforever.kuudra.config.KuudraManifest;
import io.github.actforever.kuudra.plugin.*;
import io.github.actforever.kuudra.runtime.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Serial App-owned reconciler for v1alpha2 Ability claims and Resource lifecycles. */
final class AbilityManager implements AutoCloseable {
    enum State { ENABLED, PAUSED, DISABLED, FAILED }
    enum ControlOverride { INHERIT, ENABLED, PAUSED, DISABLED }

    record AbilityView(String id, State state, ControlOverride directOverride, Set<String> profileClaims,
                       List<String> dependsOn, List<String> mutexWith, String detail) {
        AbilityView { profileClaims = Set.copyOf(profileClaims); dependsOn = List.copyOf(dependsOn); mutexWith = List.copyOf(mutexWith); }
    }
    record ResourceView(String id, String template, String state, Set<String> claimedBy, String detail) {
        ResourceView { claimedBy = Set.copyOf(claimedBy); }
    }

    private final KuudraManifest.Deployment deployment;
    private final List<String> selectedProfiles;
    private final DefaultPluginManager plugins;
    private final KuudraRuntime runtime;
    private final SystemEventPublisher events;
    private final Duration drainTimeout;
    private final Duration cancelGrace;
    private final Duration lifecycleTimeout;
    private final Map<String, ControlOverride> overrides = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    private final Map<KuudraManifest.ResourceId, ManagedResource> resources = new LinkedHashMap<>();
    private final Set<String> registeredAbilities = new LinkedHashSet<>();
    private final List<SourceRegistration> sourceBindings = new ArrayList<>();

    AbilityManager(KuudraManifest.Deployment deployment, List<String> selectedProfiles,
                   DefaultPluginManager plugins, KuudraRuntime runtime,
                   KuudraConfig.RuntimeSettings settings, SystemEventPublisher events) {
        this.deployment = Objects.requireNonNull(deployment); this.selectedProfiles = List.copyOf(selectedProfiles);
        this.plugins = Objects.requireNonNull(plugins); this.runtime = Objects.requireNonNull(runtime);
        this.events = Objects.requireNonNull(events);
        this.drainTimeout = Duration.ofMillis(settings.abilityDrainTimeoutMs());
        this.cancelGrace = Duration.ofMillis(settings.cancelGraceTimeoutMs());
        this.lifecycleTimeout = Duration.ofMillis(settings.resourceLifecycleTimeoutMs());
        deployment.abilities().values().forEach(ability -> {
            overrides.put(ability.qualifiedName(), ControlOverride.INHERIT);
            states.put(ability.qualifiedName(), State.DISABLED);
            details.put(ability.qualifiedName(), "no claim");
        });
        validateProfiles();
    }

    synchronized void start() { reconcile(); }

    synchronized CompletionStage<Void> control(String abilityId, ControlOverride override) {
        requireAbility(abilityId); Objects.requireNonNull(override);
        return CompletableFuture.runAsync(() -> {
            synchronized (AbilityManager.this) {
                overrides.put(abilityId, override);
                reconcile();
            }
        });
    }

    synchronized List<AbilityView> abilities() {
        return deployment.abilities().values().stream().map(ability -> {
            String id = ability.qualifiedName();
            return new AbilityView(id, states.get(id), overrides.get(id), profileClaims(id),
                    normalized(ability, ability.dependsOn()), normalized(ability, ability.mutexWith()), details.get(id));
        }).toList();
    }

    synchronized Optional<AbilityView> ability(String id) {
        return abilities().stream().filter(view -> view.id().equals(id)).findFirst();
    }

    synchronized List<ResourceView> resourceViews() {
        Map<KuudraManifest.ResourceId, Set<String>> claims = resourceClaims(effectiveStates());
        return deployment.resources().values().stream().map(resource -> {
            ManagedResource managed = resources.get(resource.id());
            return new ResourceView(resource.id().kind() + "/" + resource.id().qualifiedName(), resource.templateReference(),
                    managed == null ? "DESTROYED" : managed.state, claims.getOrDefault(resource.id(), Set.of()),
                    managed == null ? "no active Ability claim" : managed.detail);
        }).toList();
    }

    private void reconcile() {
        Map<String, State> desired = effectiveStates();
        validateMutex(desired);
        try {
            Map<KuudraManifest.ResourceId, Set<String>> claims = resourceClaims(desired);
            Set<KuudraManifest.ResourceId> needed = claims.keySet();
            validateLimits(needed, desired);
            for (KuudraManifest.ResourceId id : needed) {
                try { materialize(deployment.resources().get(id)); }
                catch (RuntimeException error) { markFailed(desired, claims.get(id), error); }
            }
            for (Map.Entry<String, State> entry : desired.entrySet()) {
                if (entry.getValue() != State.DISABLED && entry.getValue() != State.FAILED
                        && !registeredAbilities.contains(entry.getKey())) {
                    try {
                        KuudraManifest.Ability ability = abilityDefinition(entry.getKey());
                        runtime.registerAbility(compile(ability)); registeredAbilities.add(entry.getKey());
                    } catch (RuntimeException error) { markFailed(desired, Set.of(entry.getKey()), error); }
                }
            }
            // EventSource.start() is allowed to require an emitter. Bind sources after their
            // Ability graph exists but before any Resource lifecycle enters start().
            rebuildSourceBindings(desired);
            claims = resourceClaims(desired);
            reconcileResourceLifecycles(claims, desired);
            claims = resourceClaims(desired);
            for (Map.Entry<String, State> entry : desired.entrySet()) applyAbilityState(entry.getKey(), entry.getValue());
            rebuildSourceBindings(desired);
            destroyUnclaimed(claims.keySet());
            states.putAll(desired);
        } catch (RuntimeException error) {
            events.publish(SystemEvent.error("ability.reconciliation.failed", Map.of("error", error.toString())));
            throw error;
        }
    }

    private Map<String, State> effectiveStates() {
        Map<String, State> desired = new LinkedHashMap<>();
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            String id = ability.qualifiedName(); ControlOverride direct = overrides.get(id);
            desired.put(id, switch (direct) {
                case ENABLED -> State.ENABLED; case PAUSED -> State.PAUSED; case DISABLED -> State.DISABLED;
                case INHERIT -> profileClaims(id).isEmpty() ? State.DISABLED : State.ENABLED;
            });
        }
        boolean changed;
        do {
            changed = false;
            for (KuudraManifest.Ability ability : deployment.abilities().values()) {
                String id = ability.qualifiedName(); State state = desired.get(id);
                for (String dependency : normalized(ability, ability.dependsOn())) {
                    State required = desired.getOrDefault(dependency, State.DISABLED);
                    State cascaded = required == State.DISABLED || required == State.FAILED ? State.DISABLED
                            : required == State.PAUSED && state == State.ENABLED ? State.PAUSED : state;
                    if (cascaded != state) { desired.put(id, cascaded); state = cascaded; changed = true; }
                }
            }
        } while (changed);
        return desired;
    }

    private Set<String> profileClaims(String abilityId) {
        Set<String> claims = new LinkedHashSet<>();
        for (String profileName : selectedProfiles) {
            KuudraManifest.AbilityProfile profile = deployment.profiles().get(profileName);
            if (profile == null) continue;
            boolean included = profile.abilities().contains(abilityId)
                    || profile.namespaces().contains(abilityId.substring(0, abilityId.indexOf('/')));
            if (included && !profile.exclude().contains(abilityId)) claims.add(profileName);
        }
        return Set.copyOf(claims);
    }

    private void validateProfiles() {
        for (String profile : selectedProfiles) if (!deployment.profiles().containsKey(profile)) {
            throw new KuudraException("Unknown AbilityProfile selected by config: " + profile);
        }
        for (KuudraManifest.AbilityProfile profile : deployment.profiles().values()) {
            for (String id : profile.abilities()) requireAbility(id);
            for (String id : profile.exclude()) requireAbility(id);
        }
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            normalized(ability, ability.dependsOn()).forEach(this::requireAbility);
            normalized(ability, ability.mutexWith()).forEach(this::requireAbility);
        }
    }

    private void validateMutex(Map<String, State> desired) {
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            if (desired.get(ability.qualifiedName()) == State.DISABLED) continue;
            for (String mutex : normalized(ability, ability.mutexWith())) if (desired.get(mutex) != State.DISABLED) {
                throw new KuudraException("Mutually exclusive Abilities are both claimed: "
                        + ability.qualifiedName() + " and " + mutex);
            }
        }
    }

    private Map<KuudraManifest.ResourceId, Set<String>> resourceClaims(Map<String, State> desired) {
        Map<KuudraManifest.ResourceId, Set<String>> result = new LinkedHashMap<>();
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            if (desired.get(ability.qualifiedName()) == State.DISABLED
                    || desired.get(ability.qualifiedName()) == State.FAILED) continue;
            for (KuudraManifest.ResourceReference reference : referencedResources(ability)) {
                if (!deployment.resources().containsKey(reference.id())) throw new KuudraException(
                        "Ability " + ability.qualifiedName() + " references unknown Resource " + reference.id());
                result.computeIfAbsent(reference.id(), ignored -> new LinkedHashSet<>()).add(ability.qualifiedName());
            }
        }
        result.replaceAll((id, claims) -> Set.copyOf(claims)); return Map.copyOf(result);
    }

    private void validateLimits(Set<KuudraManifest.ResourceId> needed, Map<String, State> desired) {
        Map<String, Integer> appCounts = new HashMap<>();
        Map<String, KuudraManifest.ResourceId> exclusivityOwners = new HashMap<>();
        for (KuudraManifest.ResourceId id : needed) {
            KuudraManifest.Resource resource = deployment.resources().get(id);
            ResourceTemplateDefinition template = template(resource);
            if (template.policy().limitScope() == ResourceLimitScope.APP
                    && appCounts.merge(template.reference(), 1, Integer::sum) > template.policy().maxInstances()) {
                throw new KuudraException("ResourceTemplate APP maxInstances exceeded: " + template.reference());
            }
            String domain = template.policy().exclusivityDomain();
            if (!domain.isBlank()) {
                KuudraManifest.ResourceId previous = exclusivityOwners.putIfAbsent(domain, id);
                if (previous != null && !previous.equals(id)) throw new KuudraException(
                        "Resource exclusivityDomain conflict " + domain + ": " + previous + " and " + id);
            }
        }
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            if (desired.get(ability.qualifiedName()) == State.DISABLED) continue;
            Map<String, Long> counts = referencedResources(ability).stream().map(reference -> deployment.resources().get(reference.id()))
                    .map(this::template).filter(template -> template.policy().limitScope() == ResourceLimitScope.ABILITY)
                    .collect(java.util.stream.Collectors.groupingBy(ResourceTemplateDefinition::reference,
                            java.util.stream.Collectors.counting()));
            counts.forEach((reference, count) -> {
                int max = plugins.resourceTemplates().find(reference).orElseThrow().policy().maxInstances();
                if (count > max) throw new KuudraException("ResourceTemplate ABILITY maxInstances exceeded: " + reference);
            });
        }
    }

    private void materialize(KuudraManifest.Resource resource) {
        if (resources.containsKey(resource.id())) return;
        ResourceTemplateDefinition template = template(resource);
        Object instance = plugins.createResource(template.reference(), Object.class);
        ResourceLifecycle lifecycle = (ResourceLifecycle) instance;
        try {
            invoke("initialize", () -> lifecycle.initialize(plugins.resourceContext(template.reference(),
                    resource.id().kind() + "/" + resource.id().qualifiedName(), resource.options())));
        } catch (RuntimeException error) {
            try { invoke("destroy-after-initialize-failure", lifecycle::destroy); }
            catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
        runtime.setComponentThreadSafe(instance, template.policy().allowParallel());
        resources.put(resource.id(), new ManagedResource(resource, template, instance, lifecycle));
        events.publish(SystemEvent.debug("resource.initialized", Map.of("resource", resource.id().toString(),
                "template", template.reference())));
    }

    private void reconcileResourceLifecycles(Map<KuudraManifest.ResourceId, Set<String>> claims,
                                             Map<String, State> desired) {
        // Start downstream consumers before sources can emit their first Event.
        List<Map.Entry<KuudraManifest.ResourceId, Set<String>>> orderedClaims = claims.entrySet().stream()
                .sorted(Comparator.comparing(entry -> deployment.resources().get(entry.getKey()).type().equals("event-source")))
                .toList();
        for (Map.Entry<KuudraManifest.ResourceId, Set<String>> entry : orderedClaims) {
            ManagedResource managed = resources.get(entry.getKey());
            boolean running = entry.getValue().stream().anyMatch(id -> desired.get(id) == State.ENABLED);
            try {
                if (!managed.started) { invoke("start", managed.lifecycle::start); managed.started = true; }
                if (running && managed.paused) { invoke("resume", managed.lifecycle::resume); managed.paused = false; }
                if (!running && !managed.paused) { invoke("pause", managed.lifecycle::pause); managed.paused = true; }
                managed.state = running ? "RUNNING" : "PAUSED"; managed.detail = "claimed by " + entry.getValue();
            } catch (RuntimeException error) {
                managed.state = "FAILED"; managed.detail = error.toString();
                markFailed(desired, entry.getValue(), error);
            }
        }
    }

    private void markFailed(Map<String, State> desired, Set<String> abilityIds, RuntimeException error) {
        for (String id : abilityIds) {
            desired.put(id, State.FAILED);
            details.put(id, error.toString());
            events.publish(SystemEvent.error("ability.reconciliation.failed", Map.of(
                    "ability", id, "error", error.toString())));
        }
    }

    private void applyAbilityState(String id, State desired) {
        State previous = states.get(id);
        if (desired == State.DISABLED || desired == State.FAILED) {
            if (registeredAbilities.contains(id)) disableRegistered(id);
            if (desired == State.DISABLED) details.put(id, "disabled");
        } else if (desired == State.PAUSED) {
            runtime.setAbilityPaused(id, true); details.put(id, "paused by claim or dependency");
        } else {
            runtime.setAbilityEnabled(id, true); runtime.setAbilityPaused(id, false); details.put(id, "enabled");
        }
        if (previous != desired) events.publish(SystemEvent.of("ability.state.changed", Map.of(
                "ability", id, "from", previous.name(), "to", desired.name())));
    }

    private void disableRegistered(String id) {
        runtime.setAbilityEnabled(id, false); runtime.cancelAbilitySessions(id);
        try {
            if (!runtime.awaitAbilityDrained(id, drainTimeout)) {
                runtime.cancelAbilitySessions(id);
                if (!runtime.awaitAbilityDrained(id, cancelGrace)) throw new KuudraException(
                        "Ability did not drain after cancellation grace: " + id);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new KuudraException("Interrupted while draining Ability " + id, error);
        }
        closeSourceBindings();
        runtime.unregisterAbility(id); registeredAbilities.remove(id);
    }

    private void destroyUnclaimed(Set<KuudraManifest.ResourceId> needed) {
        List<KuudraManifest.ResourceId> stale = resources.keySet().stream().filter(id -> !needed.contains(id)).toList();
        for (KuudraManifest.ResourceId id : stale) {
            ManagedResource managed = resources.remove(id);
            if (managed.started) invoke("stop", managed.lifecycle::stop);
            invoke("destroy", managed.lifecycle::destroy);
            events.publish(SystemEvent.debug("resource.destroyed", Map.of("resource", id.toString())));
        }
    }

    private KuudraAbility compile(KuudraManifest.Ability ability) {
        Map<String, FlowNode> nodes = new LinkedHashMap<>();
        Map<String, EventDomain> adapterDomains = inferAdapterDomains(ability);
        for (Map.Entry<String, KuudraManifest.AbilityNode> entry : ability.nodes().entrySet()) {
            String nodeId = entry.getKey(); KuudraManifest.AbilityNode node = entry.getValue();
            KuudraManifest.Resource resource = claimedResource(node.resource());
            ManagedResource managed = resources.get(resource.id());
            FlowNode compiled = switch (resource.type()) {
                case "event-source" -> new FlowNode.SourceNode(nodeId, (EventSource) managed.instance);
                case "event-adapter" -> new FlowNode.AdapterNode(nodeId, (EventAdapter) managed.instance,
                        adapterDomains.get(nodeId), node.arguments());
                case "event-interpreter" -> new FlowNode.InterpreterNode(nodeId,
                        (EventInterpreter) managed.instance, node.arguments());
                case "ingress" -> ingressNode(nodeId, resource, (Ingress) managed.instance, node);
                case "controller" -> controllerNode(nodeId, managed, node);
                case "egress" -> new FlowNode.EgressNode(nodeId, (Egress) managed.instance, node.arguments());
                default -> throw new KuudraException("Unsupported Resource type: " + resource.type());
            };
            nodes.put(nodeId, compiled);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (KuudraConfig.EdgeConfig edge : ability.edges()) {
            if (!(nodes.get(edge.from()) instanceof FlowNode.SourceNode)) {
                edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            }
        }
        return new KuudraAbility(ability.qualifiedName(), 1, nodes, edges, ability.executionClass());
    }

    private FlowNode ingressNode(String nodeId, KuudraManifest.Resource resource, Ingress ingress,
                                 KuudraManifest.AbilityNode node) {
        if (node.session() == null) throw new KuudraException("Ingress node must declare session mode: " + nodeId);
        if (node.session().mode() == io.github.actforever.kuudra.api.session.SessionAdmissionMode.JOIN) {
            return new FlowNode.JoinIngressNode(nodeId, resource.id().kind() + "/" + resource.id().qualifiedName(),
                    ingress, node.session().targetIngress(), node.arguments());
        }
        return new FlowNode.IngressNode(nodeId, resource.id().kind() + "/" + resource.id().qualifiedName(),
                ingress, node.session().scheduling(), node.session().dependencies(), node.arguments());
    }

    private FlowNode controllerNode(String nodeId, ManagedResource managed, KuudraManifest.AbilityNode node) {
        if (node.handler().isBlank()) throw new KuudraException("Controller node must select handler: " + nodeId);
        ControllerHandlerDefinition handler = managed.template.handler(node.handler());
        return new FlowNode.ControllerNode(nodeId, managed.instance, handler.name(),
                (event, context) -> handler.invoke(managed.instance, event, context), node.arguments());
    }

    private void rebuildSourceBindings(Map<String, State> desired) {
        closeSourceBindings();
        Map<KuudraManifest.ResourceId, List<KuudraRuntime.SourceTarget>> targets = new LinkedHashMap<>();
        for (KuudraManifest.Ability ability : deployment.abilities().values()) {
            if ((desired.get(ability.qualifiedName()) == State.DISABLED
                    || desired.get(ability.qualifiedName()) == State.FAILED)
                    || !registeredAbilities.contains(ability.qualifiedName())) continue;
            for (KuudraConfig.EdgeConfig edge : ability.edges()) {
                KuudraManifest.Resource source = claimedResource(ability.nodes().get(edge.from()).resource());
                if (source.type().equals("event-source")) targets.computeIfAbsent(source.id(), ignored -> new ArrayList<>())
                        .add(new KuudraRuntime.SourceTarget(ability.qualifiedName(), edge.to()));
            }
        }
        targets.forEach((id, bindings) -> sourceBindings.add(runtime.bindSource(bindings,
                (EventSource) resources.get(id).instance)));
    }

    private void closeSourceBindings() {
        List<SourceRegistration> copy = new ArrayList<>(sourceBindings); sourceBindings.clear();
        Collections.reverse(copy); copy.forEach(binding -> invoke("unbind-source", binding::unregister));
    }

    private Map<String, EventDomain> inferAdapterDomains(KuudraManifest.Ability ability) {
        Map<String, EventDomain> domains = new LinkedHashMap<>(); boolean changed;
        do {
            changed = false;
            for (KuudraConfig.EdgeConfig edge : ability.edges()) {
                String fromType = claimedResource(ability.nodes().get(edge.from()).resource()).type();
                String toType = claimedResource(ability.nodes().get(edge.to()).resource()).type();
                EventDomain output = fromType.equals("event-adapter") ? domains.get(edge.from()) : fixedOutput(fromType);
                EventDomain input = toType.equals("event-adapter") ? domains.get(edge.to()) : fixedInput(toType);
                if (fromType.equals("event-adapter") && output == null && input != null) { domains.put(edge.from(), input); changed = true; }
                if (toType.equals("event-adapter") && input == null && output != null) { domains.put(edge.to(), output); changed = true; }
                if (output != null && input != null && output != input) throw new KuudraException(
                        "Ability domain mismatch: " + edge.from() + " -> " + edge.to());
            }
        } while (changed);
        ability.nodes().forEach((id, node) -> {
            if (claimedResource(node.resource()).type().equals("event-adapter") && !domains.containsKey(id)) {
                throw new KuudraException("Cannot infer EventAdapter domain: " + ability.qualifiedName() + "/" + id);
            }
        });
        return domains;
    }

    private static EventDomain fixedInput(String type) {
        return switch (type) { case "event-source" -> null; case "event-interpreter", "ingress" -> EventDomain.RAW;
            case "controller", "egress" -> EventDomain.SESSION; default -> null; };
    }
    private static EventDomain fixedOutput(String type) {
        return switch (type) { case "event-source", "event-interpreter", "egress" -> EventDomain.RAW;
            case "ingress", "controller" -> EventDomain.SESSION; default -> null; };
    }

    private Set<KuudraManifest.ResourceReference> referencedResources(KuudraManifest.Ability ability) {
        return ability.nodes().values().stream().map(KuudraManifest.AbilityNode::resource)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private KuudraManifest.Resource claimedResource(KuudraManifest.ResourceReference reference) {
        KuudraManifest.Resource resource = deployment.resources().get(reference.id());
        if (resource == null) throw new KuudraException("Unknown claimed Resource: " + reference.id());
        if (!resource.id().kind().equals(reference.kind())) throw new KuudraException(
                "Resource kind mismatch: " + reference.canonicalName());
        return resource;
    }

    private ResourceTemplateDefinition template(KuudraManifest.Resource resource) {
        ResourceTemplateDefinition template = plugins.resourceTemplates().find(resource.templateReference())
                .orElseThrow(() -> new KuudraException("Unknown ResourceTemplate: " + resource.templateReference()));
        String expected = ResourceTemplateKind.fromManifestKind(resource.id().kind()).prefix();
        if (!template.kind().prefix().equals(expected)) throw new KuudraException("ResourceTemplate kind mismatch: " + template.reference());
        return template;
    }

    private KuudraManifest.Ability abilityDefinition(String id) {
        return deployment.abilities().values().stream().filter(ability -> ability.qualifiedName().equals(id))
                .findFirst().orElseThrow(() -> new KuudraException("Unknown Ability: " + id));
    }
    private void requireAbility(String id) { abilityDefinition(id); }
    private static List<String> normalized(KuudraManifest.Ability owner, List<String> values) {
        return values.stream().map(value -> value.contains("/") ? value : owner.id().namespace() + "/" + value).toList();
    }

    private void invoke(String operation, Supplier<CompletionStage<Void>> action) {
        try {
            CompletionStage<Void> stage = action.get();
            if (stage == null) throw new KuudraException("Resource lifecycle returned null: " + operation);
            stage.toCompletableFuture().orTimeout(lifecycleTimeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException error) {
            throw KuudraException.wrap("Resource lifecycle " + operation + " failed", error.getCause());
        }
    }

    @Override public synchronized void close() {
        closeSourceBindings();
        List<String> abilities = new ArrayList<>(registeredAbilities); Collections.reverse(abilities);
        for (String id : abilities) {
            try { disableRegistered(id); } catch (RuntimeException error) {
                events.publish(SystemEvent.error("ability.shutdown.failed", Map.of("ability", id, "error", error.toString())));
            }
        }
        List<KuudraManifest.ResourceId> ids = new ArrayList<>(resources.keySet()); Collections.reverse(ids);
        for (KuudraManifest.ResourceId id : ids) {
            ManagedResource managed = resources.remove(id);
            if (managed.started) invoke("stop", managed.lifecycle::stop);
            invoke("destroy", managed.lifecycle::destroy);
        }
    }

    private static final class ManagedResource {
        final KuudraManifest.Resource definition; final ResourceTemplateDefinition template;
        final Object instance; final ResourceLifecycle lifecycle;
        boolean started; boolean paused; String state = "INITIALIZED"; String detail = "initialized";
        ManagedResource(KuudraManifest.Resource definition, ResourceTemplateDefinition template,
                        Object instance, ResourceLifecycle lifecycle) {
            this.definition = definition; this.template = template; this.instance = instance; this.lifecycle = lifecycle;
        }
    }
}
