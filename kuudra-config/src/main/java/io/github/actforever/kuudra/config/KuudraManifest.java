package io.github.actforever.kuudra.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Format-neutral K8s-style resource manifests discovered under the Kuudra home. */
public final class KuudraManifest {
    public static final String API_VERSION = "kuudra.io/v1alpha1";

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

    public record Component(ResourceId id, Metadata metadata, String type, String component,
                            String desiredState, Map<String, Object> options) {
        public Component {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            requireText(type, "spec.type"); requireText(component, "spec.component"); requireText(desiredState, "spec.desiredState");
            options = Map.copyOf(options);
        }
    }

    public record Flow(ResourceId id, Metadata metadata, String desiredState,
                       Map<String, ResourceReference> imports, List<KuudraConfig.EdgeConfig> edges) {
        public Flow {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            requireText(desiredState, "spec.desiredState"); imports = Map.copyOf(imports); edges = List.copyOf(edges);
            if (imports.isEmpty()) throw new IllegalArgumentException("Flow imports must not be empty");
            for (KuudraConfig.EdgeConfig edge : edges) {
                if (!imports.containsKey(edge.from())) throw new IllegalArgumentException("Unknown Flow edge source import: " + edge.from());
                if (!imports.containsKey(edge.to())) throw new IllegalArgumentException("Unknown Flow edge target import: " + edge.to());
            }
        }
    }

    public record Resources(Map<ResourceId, Component> components, Map<ResourceId, Flow> flows) {
        public static final Resources EMPTY = new Resources(Map.of(), Map.of());
        public Resources { components = Map.copyOf(components); flows = Map.copyOf(flows); }
        public boolean isEmpty() { return components.isEmpty() && flows.isEmpty(); }
    }

    private static void requireDnsLabel(String value, String location) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalArgumentException(location + " must match [a-z0-9][a-z0-9-]*");
    }
    private static void requireText(String value, String location) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(location + " must not be blank");
    }
}
