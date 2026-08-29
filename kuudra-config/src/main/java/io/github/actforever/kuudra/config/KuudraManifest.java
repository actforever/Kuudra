package io.github.actforever.kuudra.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import io.github.actforever.kuudra.api.component.IngressConfiguration;
import io.github.actforever.kuudra.api.runtime.FlowExecutionClass;
import io.github.actforever.kuudra.api.session.SessionDependencyRequirement;

/** Format-neutral K8s-style resource manifests discovered under the Kuudra home. */
public final class KuudraManifest {
    public static final String API_VERSION = "kuudra.io/v1alpha2";
    public static final String LEGACY_API_VERSION = "kuudra.io/v1alpha1";
    public static final Map<String, String> COMPONENT_KINDS = Map.of(
            "EventSource", "event-source", "EventInterpreter", "event-interpreter",
            "EventAdapter", "event-adapter", "Ingress", "ingress",
            "EventHandler", "event-handler", "Egress", "egress");
    public static final Map<String, String> RESOURCE_KINDS = Map.of(
            "EventSource", "event-source", "EventInterpreter", "event-interpreter",
            "EventAdapter", "event-adapter", "Ingress", "ingress",
            "Controller", "controller", "Egress", "egress");

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

    /** Static App-owned resource declaration. */
    public record Resource(ResourceId id, Metadata metadata, String template, Map<String, Object> options) {
        public Resource {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            if (!RESOURCE_KINDS.containsKey(id.kind())) {
                throw new IllegalArgumentException("Unsupported Resource kind: " + id.kind());
            }
            requireText(template, "spec.template");
            String[] identity = template.split("/", -1);
            if (identity.length != 3 || java.util.Arrays.stream(identity).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException(
                        "spec.template must be plugin-namespace/plugin-id/template-name: " + template);
            }
            options = Map.copyOf(options);
            rejectPlaceholders(options, "spec.options");
        }
        public String type() { return RESOURCE_KINDS.get(id.kind()); }
        public String templateReference() { return type() + "/" + template; }
    }

    public record AbilityResource(ResourceReference reference) {
        public AbilityResource { Objects.requireNonNull(reference, "reference"); }
    }

    public record IngressSession(io.github.actforever.kuudra.api.session.SessionAdmissionMode mode,
                                 String targetIngress, IngressConfiguration scheduling,
                                 List<SessionDependencyRequirement> dependencies) {
        public IngressSession {
            Objects.requireNonNull(mode, "mode");
            targetIngress = targetIngress == null ? "" : targetIngress;
            dependencies = List.copyOf(dependencies);
            if (mode == io.github.actforever.kuudra.api.session.SessionAdmissionMode.CREATE) {
                if (!targetIngress.isEmpty()) throw new IllegalArgumentException("CREATE must not set targetIngress");
                Objects.requireNonNull(scheduling, "CREATE scheduling");
            } else {
                requireText(targetIngress, "JOIN targetIngress");
                if (scheduling != null || !dependencies.isEmpty()) {
                    throw new IllegalArgumentException("JOIN must not declare scheduling or dependencies");
                }
            }
        }
    }

    /** One routed invocation. Controller nodes select a named handler. */
    public record AbilityNode(String resource, String handler, Map<String, Object> arguments,
                              IngressSession session) {
        public AbilityNode {
            requireText(resource, "node.resource");
            handler = handler == null ? "" : handler;
            arguments = Map.copyOf(arguments);
        }
    }

    public record Ability(ResourceId id, Metadata metadata,
                          io.github.actforever.kuudra.api.runtime.AbilityExecutionClass executionClass,
                          Map<String, AbilityResource> resources, Map<String, AbilityNode> nodes,
                          List<KuudraConfig.EdgeConfig> edges,
                          List<String> dependsOn, List<String> mutexWith) {
        public Ability {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(metadata, "metadata");
            if (!"Ability".equals(id.kind())) throw new IllegalArgumentException("Invalid Ability kind: " + id.kind());
            Objects.requireNonNull(executionClass, "executionClass");
            resources = Map.copyOf(resources); nodes = Map.copyOf(nodes); edges = List.copyOf(edges);
            dependsOn = uniqueNames(dependsOn, "dependsOn"); mutexWith = uniqueNames(mutexWith, "mutexWith");
            if (resources.isEmpty() || nodes.isEmpty()) throw new IllegalArgumentException("Ability resources and nodes must not be empty");
            for (Map.Entry<String, AbilityNode> entry : nodes.entrySet()) {
                String nodeId = entry.getKey(); AbilityNode node = entry.getValue();
                requireDnsLabel(nodeId, "node id");
                if (!resources.containsKey(node.resource())) throw new IllegalArgumentException(
                        "Unknown Ability node Resource alias: " + node.resource());
            }
            Set<KuudraConfig.EdgeConfig> uniqueEdges = new HashSet<>();
            for (KuudraConfig.EdgeConfig edge : edges) {
                if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                    throw new IllegalArgumentException("Ability edge must reference declared nodes: " + edge);
                }
                if (!uniqueEdges.add(edge)) throw new IllegalArgumentException("Duplicate Ability edge: " + edge);
            }
            for (Map.Entry<String, AbilityNode> entry : nodes.entrySet()) {
                AbilityNode node = entry.getValue();
                if (node.session() != null && node.session().mode()
                        == io.github.actforever.kuudra.api.session.SessionAdmissionMode.JOIN) {
                    AbilityNode target = nodes.get(node.session().targetIngress());
                    if (target == null || target.session() == null || target.session().mode()
                            != io.github.actforever.kuudra.api.session.SessionAdmissionMode.CREATE) {
                        throw new IllegalArgumentException("JOIN targetIngress must reference a CREATE node in the same Ability: "
                                + node.session().targetIngress());
                    }
                }
            }
            validateSameNamespace(id.namespace(), dependsOn, "dependsOn");
            validateSameNamespace(id.namespace(), mutexWith, "mutexWith");
        }
        public String qualifiedName() { return id.qualifiedName(); }
    }

    /** Global profile; profile names have no namespace. */
    public record AbilityProfile(String name, List<String> abilities, List<String> namespaces,
                                 List<String> exclude) {
        public AbilityProfile {
            requireDnsLabel(name, "profile name");
            abilities = uniqueNames(abilities, "abilities");
            namespaces = uniqueNames(namespaces, "namespaces");
            exclude = uniqueNames(exclude, "exclude");
        }
    }

    public record Deployment(Map<ResourceId, Resource> resources, Map<ResourceId, Ability> abilities,
                             Map<String, AbilityProfile> profiles) {
        public static final Deployment EMPTY = new Deployment(Map.of(), Map.of(), Map.of());
        public Deployment {
            resources = Map.copyOf(resources); abilities = Map.copyOf(abilities); profiles = Map.copyOf(profiles);
        }
        public boolean isEmpty() { return resources.isEmpty() && abilities.isEmpty() && profiles.isEmpty(); }
    }

    private static List<String> uniqueNames(List<String> values, String field) {
        List<String> copy = List.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())
                || copy.stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain unique non-blank names");
        }
        return copy;
    }

    private static void validateSameNamespace(String namespace, List<String> values, String field) {
        for (String value : values) {
            String[] parts = value.split("/", -1);
            if (parts.length == 2 && !parts[0].equals(namespace)) {
                throw new IllegalArgumentException(field + " may reference only the same namespace: " + value);
            }
            if (parts.length > 2) throw new IllegalArgumentException("Invalid Ability reference: " + value);
        }
    }

    private static void rejectPlaceholders(Object value, String path) {
        if (value instanceof String text && text.contains("${")) {
            throw new IllegalArgumentException(path + " is static and must not contain placeholders");
        }
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> rejectPlaceholders(item, path + "." + key));
        if (value instanceof List<?> list) for (int index = 0; index < list.size(); index++) {
            rejectPlaceholders(list.get(index), path + "[" + index + "]");
        }
    }

    private static void requireDnsLabel(String value, String location) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalArgumentException(location + " must match [a-z0-9][a-z0-9-]*");
    }
    private static void requireText(String value, String location) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(location + " must not be blank");
    }
}
