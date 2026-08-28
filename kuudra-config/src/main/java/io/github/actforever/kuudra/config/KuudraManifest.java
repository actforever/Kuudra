package io.github.actforever.kuudra.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.actforever.kuudra.api.component.IngressConfiguration;
import io.github.actforever.kuudra.api.runtime.FlowExecutionClass;
import io.github.actforever.kuudra.api.session.SessionDependencyRequirement;

/** Format-neutral K8s-style resource manifests discovered under the Kuudra home. */
public final class KuudraManifest {
    public static final String API_VERSION = "kuudra.io/v1alpha1";
    public static final Map<String, String> COMPONENT_KINDS = Map.of(
            "EventSource", "event-source", "EventInterpreter", "event-interpreter",
            "EventAdapter", "event-adapter", "Ingress", "ingress",
            "EventHandler", "event-handler", "Egress", "egress");

    private KuudraManifest() { }

    public record ResourceId(String kind, String namespace, String name) {
        public ResourceId {
            requireDnsLabel(namespace, "namespace"); requireDnsLabel(name, "name");
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
        }
        public String qualifiedName() { return namespace + "/" + name; }
    }

    public record Metadata(String namespace, String name, Map<String, String> labels, Map<String, String> annotations) {
        public Metadata {
            requireDnsLabel(namespace, "metadata.namespace"); requireDnsLabel(name, "metadata.name");
            labels = Map.copyOf(labels); annotations = Map.copyOf(annotations);
        }
    }

    public record ResourceReference(String kind, String namespace, String name) {
        public ResourceReference {
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("reference.kind must not be blank");
            requireDnsLabel(namespace, "reference.namespace"); requireDnsLabel(name, "reference.name");
        }
        public ResourceId id() { return new ResourceId(kind, namespace, name); }
    }

    public record Component(ResourceId id, Metadata metadata, String component,
                            String desiredState, Map<String, Object> options) {
        public Component {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            if (!COMPONENT_KINDS.containsKey(id.kind())) throw new IllegalArgumentException("Unsupported component kind: " + id.kind());
            requireText(component, "spec.component"); requireText(desiredState, "spec.desiredState");
            String[] identity = component.split("/", -1);
            if (identity.length != 3 || java.util.Arrays.stream(identity).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("spec.component must be plugin-namespace/plugin-id/component-name: " + component);
            }
            options = Map.copyOf(options);
        }
        public String type() { return COMPONENT_KINDS.get(id.kind()); }
    }

    public record Flow(ResourceId id, Metadata metadata, FlowExecutionClass executionClass,
                       Map<String, ResourceReference> imports, List<KuudraConfig.EdgeConfig> edges) {
        public Flow(ResourceId id, Metadata metadata,
                    Map<String, ResourceReference> imports, List<KuudraConfig.EdgeConfig> edges) {
            this(id, metadata, FlowExecutionClass.DATA, imports, edges);
        }
        public Flow {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(executionClass, "executionClass");
            imports = Map.copyOf(imports); edges = List.copyOf(edges);
            if (imports.isEmpty()) throw new IllegalArgumentException("Flow imports must not be empty");
            for (ResourceReference reference : imports.values()) {
                if (!COMPONENT_KINDS.containsKey(reference.kind())) throw new IllegalArgumentException("Flow import kind must be a concrete component kind: " + reference.kind());
            }
            var uniqueEdges = new HashSet<KuudraConfig.EdgeConfig>();
            for (KuudraConfig.EdgeConfig edge : edges) {
                if (!imports.containsKey(edge.from())) throw new IllegalArgumentException("Unknown Flow edge source import: " + edge.from());
                if (!imports.containsKey(edge.to())) throw new IllegalArgumentException("Unknown Flow edge target import: " + edge.to());
                if (!uniqueEdges.add(edge)) throw new IllegalArgumentException(
                        "Duplicate Flow edge: " + edge.from() + " -> " + edge.to());
            }
        }
    }

    public record CoordinationPolicy(ResourceId id, Metadata metadata, Map<String, String> matchLabels,
                                     IngressConfiguration scheduling,
                                     List<SessionDependencyRequirement> dependencies) {
        public CoordinationPolicy {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            if (!"SessionCoordinationPolicy".equals(id.kind())) throw new IllegalArgumentException("Invalid policy kind: " + id.kind());
            matchLabels = Map.copyOf(matchLabels);
            if (matchLabels.isEmpty()) throw new IllegalArgumentException("spec.selector.matchLabels must not be empty");
            Objects.requireNonNull(scheduling, "scheduling");
            dependencies = List.copyOf(dependencies);
        }
    }

    public record Resources(Map<ResourceId, Component> components, Map<ResourceId, Flow> flows,
                            Map<ResourceId, CoordinationPolicy> coordinationPolicies) {
        public static final Resources EMPTY = new Resources(Map.of(), Map.of(), Map.of());
        public Resources {
            components = Map.copyOf(components); flows = Map.copyOf(flows);
            coordinationPolicies = Map.copyOf(coordinationPolicies);
        }
        public boolean isEmpty() { return components.isEmpty() && flows.isEmpty() && coordinationPolicies.isEmpty(); }
    }

    private static void requireDnsLabel(String value, String location) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalArgumentException(location + " must match [a-z0-9][a-z0-9-]*");
    }
    private static void requireText(String value, String location) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(location + " must not be blank");
    }
}
