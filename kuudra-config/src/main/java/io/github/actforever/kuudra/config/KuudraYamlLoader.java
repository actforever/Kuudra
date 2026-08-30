package io.github.actforever.kuudra.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import io.github.actforever.kuudra.api.session.SessionGroupScope;
import io.github.actforever.kuudra.api.session.SessionSchedulingPolicy;
import io.github.actforever.kuudra.api.component.IngressConfiguration;

/** Reads config.yaml plus Resource, Ability and AbilityProfile YAML into the format-neutral model. */
public final class KuudraYamlLoader {
    private KuudraYamlLoader() { }

    public static KuudraConfig.RuntimeConfig load(Path file) throws IOException {
        return load(readResource(file));
    }

    public static KuudraConfigResource readResource(Path file) throws IOException {
        Path configFile = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (!Files.isRegularFile(configFile)) throw new IOException("Kuudra configuration does not exist: " + configFile);
        return new KuudraConfigResource(mapping(read(configFile), configFile), configFile.getParent(), configFile.toString());
    }

    public static KuudraConfigResource readResource(InputStream input, Path baseDirectory, String description) throws IOException {
        Objects.requireNonNull(input, "input");
        try (Reader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            return new KuudraConfigResource(mapping(new Yaml().load(reader), description), baseDirectory, description);
        }
    }

    /** Deeply merges resources from lowest to highest priority using one path-resolution base. */
    public static KuudraConfigResource merge(Path baseDirectory, String description, KuudraConfigResource... resources) throws IOException {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (KuudraConfigResource resource : resources) {
            if (resource != null) mergeMappings(merged, resource.values());
        }
        return new KuudraConfigResource(merged, baseDirectory, description);
    }

    /** Compiles a framework-neutral configuration resource after its host has merged any overrides. */
    public static KuudraConfig.RuntimeConfig load(KuudraConfigResource resource) throws IOException {
        Objects.requireNonNull(resource, "resource");
        Path base = resource.baseDirectory();
        Map<String, Object> root = resource.values();
        if (root.containsKey("plugins")) throw new IOException("Configuration key 'plugins' has been removed; use <home-directory>/plugins");
        if (root.containsKey("flows-directory")) throw new IOException("Configuration key 'flows-directory' has been removed; use <home-directory>/manifests");
        if (root.containsKey("resource-selection")) throw new IOException(
                "Configuration key 'resource-selection' has been removed; use ability-profiles");
        Map<String, Object> runtime = optionalMapping(root, "runtime");
        int queueCapacity = integer(runtime, "queue-capacity", 1_024);
        int workerThreads = integer(runtime, "worker-threads", Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        int maxEventHops = integer(runtime, "max-event-hops", 256);
        int dispatcherPollIntervalMs = integer(runtime, "dispatcher-poll-interval-ms", 200);
        int shutdownSessionDrainTimeoutMs = nonNegativeInteger(runtime, "shutdown-session-drain-timeout-ms", 5_000);
        int abilityDrainTimeoutMs = nonNegativeInteger(runtime, "ability-drain-timeout-ms", 5_000);
        int cancelGraceTimeoutMs = nonNegativeInteger(runtime, "cancel-grace-timeout-ms", 5_000);
        int resourceLifecycleTimeoutMs = integer(runtime, "resource-lifecycle-timeout-ms", 120_000);
        if (runtime.containsKey("session-coordinator")) throw new IOException(
                "runtime.session-coordinator has been removed; configure scheduling on CREATE Ingress nodes");
        Map<String, Object> coordinator = optionalMapping(runtime, "session-coordinator");
        SessionSchedulingPolicy defaultPolicy = enumValue(coordinator, "default-policy", SessionSchedulingPolicy.PARALLEL, SessionSchedulingPolicy.class);
        SessionGroupScope defaultGroupScope = enumValue(coordinator, "default-group-scope", SessionGroupScope.INGRESS, SessionGroupScope.class);
        int maxParallelSessions = integer(coordinator, "max-parallel-sessions", 64);
        int sessionQueueCapacity = integer(coordinator, "queue-capacity", 256);
        Map<String, Object> resourceSelection = Map.of();
        KuudraConfig.NamespaceMode namespaceMode = enumValue(resourceSelection, "namespace-mode",
                KuudraConfig.NamespaceMode.ALL, KuudraConfig.NamespaceMode.class);
        java.util.Set<String> selectedNamespaces = stringSet(resourceSelection.get("namespaces"), "resource-selection.namespaces");
        Map<String, Object> reconciliation = optionalMapping(root, "reconciliation");
        boolean reconciliationEnabled = bool(reconciliation.get("enabled"), true);
        int reconciliationIntervalMs = integer(reconciliation, "interval-ms", 1_000);
        Map<String, Object> stateStore = optionalMapping(root, "state-store");
        int stateStoreBusyTimeoutMs = nonNegativeInteger(stateStore, "busy-timeout-ms", 5_000);
        Map<String, Object> logging = optionalMapping(root, "logging");
        String loggingLevel = string(logging.getOrDefault("level", "info"), "logging.level").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF").contains(loggingLevel)) {
            throw new IOException("Unsupported logging.level: " + loggingLevel);
        }
        boolean consoleEnabled = bool(logging.get("console-enabled"), true);
        boolean fileEnabled = bool(logging.get("file-enabled"), true);
        Map<String, Object> i18n = optionalMapping(root, "i18n");
        String preferredLocale = string(i18n.getOrDefault("preferred-locale", "en_US"), "i18n.preferred-locale");
        if (!preferredLocale.matches("[a-z]{2}_[A-Z]{2}")) {
            throw new IOException("Expected locale in xx_XX format at i18n.preferred-locale: " + preferredLocale);
        }
        Path homeDirectory = base.resolve(string(root.getOrDefault("home-directory", ".kuudra"), "home-directory")).normalize();
        boolean bannerEnabled = bool(root.get("banner-enabled"), true);
        KuudraManifest.Deployment deployment = loadDeployment(homeDirectory);
        List<String> abilityProfiles = strings(root, "ability-profiles");
        List<String> abilities = abilityReferences(root, "abilities");
        return new KuudraConfig.RuntimeConfig(new KuudraConfig.RuntimeSettings(queueCapacity, workerThreads, maxEventHops,
                dispatcherPollIntervalMs, shutdownSessionDrainTimeoutMs,
                new KuudraConfig.SessionCoordinatorSettings(defaultPolicy, defaultGroupScope, maxParallelSessions, sessionQueueCapacity),
                abilityDrainTimeoutMs, cancelGraceTimeoutMs, resourceLifecycleTimeoutMs),
                new KuudraConfig.ResourceSelectionSettings(namespaceMode, selectedNamespaces),
                new KuudraConfig.ReconciliationSettings(reconciliationEnabled, reconciliationIntervalMs),
                new KuudraConfig.StateStoreSettings(stateStoreBusyTimeoutMs),
                new KuudraConfig.LoggingSettings(loggingLevel, consoleEnabled, fileEnabled),
                new KuudraConfig.I18nSettings(preferredLocale), homeDirectory, bannerEnabled,
                optionalMapping(root, "global-context"), KuudraManifest.Resources.EMPTY, deployment,
                abilityProfiles, abilities);
    }

    private static java.util.Set<String> stringSet(Object value, String path) throws IOException {
        if (value == null) return java.util.Set.of();
        if (!(value instanceof List<?> values)) throw new IOException("Expected sequence at " + path);
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) {
            String text = string(item, path + "[]");
            if (!result.add(text)) throw new IOException("Duplicate value at " + path + ": " + text);
        }
        return java.util.Set.copyOf(result);
    }

    /** Reloads the complete authoritative manifest set from a Kuudra home manifests directory. */
    public static KuudraManifest.Resources loadManifests(Path directory) throws IOException {
        Map<KuudraManifest.ResourceId, KuudraManifest.Component> components = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.CoordinationPolicy> policies = new LinkedHashMap<>();
        if (!Files.exists(directory)) return KuudraManifest.Resources.EMPTY;
        if (!Files.isDirectory(directory)) throw new IOException("Manifest directory is not a directory: " + directory);
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).filter(KuudraYamlLoader::isYaml).sorted().toList()) {
                int document = 0;
                for (ManifestDocument manifest : readAll(file)) {
                    document++;
                    if (manifest.value() == null) continue;
                    try {
                        loadManifest(manifest, file, document, components, flows, policies);
                    } catch (IOException invalid) {
                        throw new IOException("Invalid manifest " + file + "#document-" + document + ": "
                                + invalid.getMessage(), invalid);
                    }
                }
            }
        }
        return new KuudraManifest.Resources(components, flows, policies);
    }

    /** Loads the v0.5 authoritative deployment rooted at one Kuudra home directory. */
    public static KuudraManifest.Deployment loadDeployment(Path homeDirectory) throws IOException {
        Path home = Objects.requireNonNull(homeDirectory, "homeDirectory").toAbsolutePath().normalize();
        rejectLegacyAbilityProfiles(home.resolve("ability-profiles"));
        return loadDeployment(home.resolve("manifests"), home.resolve("abilities"),
                home.resolve("abilities/profiles"));
    }

    /** Loads the v0.5 authoritative Resource, Ability and global AbilityProfile sets. */
    public static KuudraManifest.Deployment loadDeployment(Path manifestsDirectory,
                                                           Path abilitiesDirectory,
                                                           Path profilesDirectory) throws IOException {
        Map<KuudraManifest.ResourceId, KuudraManifest.Resource> resources = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.Ability> abilities = new LinkedHashMap<>();
        Map<String, KuudraManifest.AbilityProfile> profiles = new LinkedHashMap<>();
        loadV2Directory(manifestsDirectory, V2Directory.RESOURCES, (document, file, index) ->
                loadV2Manifest(document, file, index, resources, abilities, profiles));
        loadV2Directory(abilitiesDirectory, V2Directory.ABILITIES, (document, file, index) ->
                loadV2Manifest(document, file, index, resources, abilities, profiles));
        loadV2Directory(profilesDirectory, V2Directory.PROFILES, (document, file, index) ->
                loadV2Manifest(document, file, index, resources, abilities, profiles));
        validateResourceReferences(resources, abilities);
        return new KuudraManifest.Deployment(resources, abilities, profiles);
    }

    private static void loadV2Directory(Path directory, V2Directory expected,
                                        V2DocumentConsumer consumer) throws IOException {
        if (!Files.exists(directory)) return;
        if (!Files.isDirectory(directory)) throw new IOException("Configuration path is not a directory: " + directory);
        try (Stream<Path> files = Files.walk(directory)) {
            Path profiles = directory.resolve("profiles").normalize();
            for (Path file : files.filter(Files::isRegularFile).filter(KuudraYamlLoader::isYaml)
                    .filter(path -> expected != V2Directory.ABILITIES || !path.normalize().startsWith(profiles))
                    .sorted().toList()) {
                int index = 0;
                for (ManifestDocument document : readAll(file)) {
                    index++;
                    if (document.value() == null) continue;
                    Map<String, Object> root = mapping(document.value(), file);
                    String kind = string(root.get("kind"), file + ".kind");
                    if (!expected.accepts(kind)) {
                        throw new IOException(expected.error(kind, directory, file));
                    }
                    consumer.accept(document, file, index);
                }
            }
        }
    }

    private static void loadV2Manifest(ManifestDocument document, Path file, int documentIndex,
                                       Map<KuudraManifest.ResourceId, KuudraManifest.Resource> resources,
                                       Map<KuudraManifest.ResourceId, KuudraManifest.Ability> abilities,
                                       Map<String, KuudraManifest.AbilityProfile> profiles) throws IOException {
        String source = file + ":" + document.line("") + " (document " + documentIndex + ")";
        Map<String, Object> root = mapping(document.value(), source);
        String apiVersion = string(required(root, "apiVersion", document, "",
                "apiVersion: " + KuudraManifest.API_VERSION), source + ".apiVersion");
        if (KuudraManifest.LEGACY_API_VERSION.equals(apiVersion)) {
            throw new IOException("apiVersion " + apiVersion + " is no longer accepted at " + source
                    + "; migrate Flow to Ability, EventHandler to Controller handler nodes, and spec.component to spec.template");
        }
        if (!KuudraManifest.API_VERSION.equals(apiVersion)) {
            throw new IOException("Unsupported apiVersion at " + source + ": " + apiVersion);
        }
        String kind = string(required(root, "kind"), source + ".kind");
        Map<String, Object> metadataMap = mapping(required(root, "metadata"), source + ".metadata");
        String name = string(required(metadataMap, "name"), source + ".metadata.name");
        Map<String, Object> spec = mapping(required(root, "spec"), source + ".spec");
        try {
            if ("AbilityProfile".equals(kind)) {
                if (metadataMap.containsKey("namespace")) {
                    throw new IllegalArgumentException("AbilityProfile is global and metadata.namespace is forbidden");
                }
                KuudraManifest.AbilityProfile profile = new KuudraManifest.AbilityProfile(name,
                        strings(spec, "abilities"), strings(spec, "namespaces"), strings(spec, "exclude"));
                if (profiles.putIfAbsent(name, profile) != null) throw new IllegalArgumentException("Duplicate AbilityProfile: " + name);
                return;
            }
            String namespace = string(metadataMap.getOrDefault("namespace", "default"), source + ".metadata.namespace");
            KuudraManifest.Metadata metadata = new KuudraManifest.Metadata(namespace, name,
                    stringMapping(metadataMap, "labels"), stringMapping(metadataMap, "annotations"));
            if (KuudraManifest.RESOURCE_KINDS.containsKey(kind)) {
                if (spec.containsKey("desiredState")) throw new IllegalArgumentException(
                        "Resource desired state is inferred from Ability claims; spec.desiredState is forbidden");
                KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                KuudraManifest.Resource resource = new KuudraManifest.Resource(id, metadata,
                        string(required(spec, "template"), source + ".spec.template"), optionalMapping(spec, "options"));
                if (resources.putIfAbsent(id, resource) != null) throw new IllegalArgumentException("Duplicate Resource: " + id);
                return;
            }
            if (!"Ability".equals(kind)) throw new IllegalArgumentException("Unsupported v1alpha2 kind: " + kind);
            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
            Map<String, KuudraManifest.ResourceReference> aliases = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : optionalMapping(spec, "resources").entrySet()) {
                aliases.put(entry.getKey(), resourceReference(entry.getValue(),
                        source + ".spec.resources." + entry.getKey()));
            }
            Map<String, KuudraManifest.AbilityNode> nodes = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : mapping(required(spec, "nodes"), source + ".spec.nodes").entrySet()) {
                Map<String, Object> value = mapping(entry.getValue(), source + ".spec.nodes." + entry.getKey());
                nodes.put(entry.getKey(), new KuudraManifest.AbilityNode(
                        resolveNodeResource(required(value, "resource"), aliases,
                                source + ".spec.nodes." + entry.getKey() + ".resource"),
                        value.containsKey("handler") ? string(value.get("handler"), "node.handler") : "",
                        optionalMapping(value, "arguments"),
                        value.containsKey("session") ? ingressSession(mapping(value.get("session"), "node.session"), source) : null));
            }
            List<KuudraConfig.EdgeConfig> edges = new ArrayList<>();
            for (Object item : list(required(spec, "edges"))) {
                Map<String, Object> edge = mapping(item, source + ".spec.edges[]");
                edges.add(new KuudraConfig.EdgeConfig(string(required(edge, "from"), "edge.from"),
                        string(required(edge, "to"), "edge.to")));
            }
            KuudraManifest.Ability ability = new KuudraManifest.Ability(id, metadata,
                    enumValue(io.github.actforever.kuudra.api.runtime.AbilityExecutionClass.class,
                            spec.getOrDefault("executionClass", "DATA")), aliases, nodes, edges,
                    strings(spec, "dependsOn"), strings(spec, "mutexWith"));
            if (abilities.putIfAbsent(id, ability) != null) throw new IllegalArgumentException("Duplicate Ability: " + id);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid " + kind + " at " + source + ": " + invalid.getMessage(), invalid);
        }
    }

    private static KuudraManifest.ResourceReference resolveNodeResource(
            Object value, Map<String, KuudraManifest.ResourceReference> aliases, String source) throws IOException {
        if (value instanceof String text && !text.contains("/")) {
            KuudraManifest.ResourceReference reference = aliases.get(text);
            if (reference == null) throw new IOException("Unknown Resource alias at " + source + ": " + text);
            return reference;
        }
        return resourceReference(value, source);
    }

    private static KuudraManifest.ResourceReference resourceReference(Object value, String source) throws IOException {
        try {
            if (value instanceof String text) {
                String[] identity = text.split("/", -1);
                if (identity.length != 3) throw new IllegalArgumentException(
                        "Resource reference must be kind/namespace/name: " + text);
                return newResourceReference(identity[0], identity[1], identity[2]);
            }
            Map<String, Object> object = mapping(value, source);
            return newResourceReference(
                    string(required(object, "kind"), source + ".kind"),
                    string(required(object, "namespace"), source + ".namespace"),
                    string(required(object, "name"), source + ".name"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Resource reference at " + source + ": " + invalid.getMessage(), invalid);
        }
    }

    private static KuudraManifest.ResourceReference newResourceReference(
            String kind, String namespace, String name) {
        if (!KuudraManifest.RESOURCE_KINDS.containsKey(kind)) {
            throw new IllegalArgumentException("Unsupported Resource kind: " + kind);
        }
        return new KuudraManifest.ResourceReference(kind, namespace, name);
    }

    private static void validateResourceReferences(
            Map<KuudraManifest.ResourceId, KuudraManifest.Resource> resources,
            Map<KuudraManifest.ResourceId, KuudraManifest.Ability> abilities) throws IOException {
        for (KuudraManifest.Ability ability : abilities.values()) {
            for (KuudraManifest.ResourceReference reference : ability.resources().values()) {
                requireResource(resources, ability, reference, "alias");
            }
            for (Map.Entry<String, KuudraManifest.AbilityNode> node : ability.nodes().entrySet()) {
                requireResource(resources, ability, node.getValue().resource(), "node " + node.getKey());
            }
        }
    }

    private static void requireResource(Map<KuudraManifest.ResourceId, KuudraManifest.Resource> resources,
                                        KuudraManifest.Ability ability,
                                        KuudraManifest.ResourceReference reference,
                                        String location) throws IOException {
        if (!resources.containsKey(reference.id())) throw new IOException("Ability " + ability.qualifiedName()
                + " " + location + " references unknown Resource " + reference.canonicalName());
    }

    private static void rejectLegacyAbilityProfiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            if (files.anyMatch(path -> Files.isRegularFile(path) && isYaml(path))) {
                throw new IOException("AbilityProfile directory has moved from " + directory
                        + " to <home-directory>/abilities/profiles");
            }
        }
    }

    private enum V2Directory {
        RESOURCES, ABILITIES, PROFILES;

        boolean accepts(String kind) {
            return switch (this) {
                case RESOURCES -> KuudraManifest.RESOURCE_KINDS.containsKey(kind);
                case ABILITIES -> "Ability".equals(kind);
                case PROFILES -> "AbilityProfile".equals(kind);
            };
        }

        String error(String kind, Path directory, Path file) {
            return switch (this) {
                case RESOURCES -> kind.equals("Ability")
                        ? "Ability must be stored under <home-directory>/abilities: " + file
                        : "Only Resource kinds are allowed under " + directory + ": " + file;
                case ABILITIES -> kind.equals("AbilityProfile")
                        ? "AbilityProfile must be stored under <home-directory>/abilities/profiles: " + file
                        : "Only Ability is allowed under " + directory + ": " + file;
                case PROFILES -> "Only AbilityProfile is allowed under " + directory + ": " + file;
            };
        }
    }

    private static KuudraManifest.IngressSession ingressSession(Map<String, Object> value, String source)
            throws IOException {
        io.github.actforever.kuudra.api.session.SessionAdmissionMode mode = enumValue(
                io.github.actforever.kuudra.api.session.SessionAdmissionMode.class,
                value.getOrDefault("mode", "CREATE"));
        if (mode == io.github.actforever.kuudra.api.session.SessionAdmissionMode.JOIN) {
            return new KuudraManifest.IngressSession(mode,
                    string(required(value, "targetIngress"), source + ".targetIngress"), null, List.of());
        }
        Map<String, Object> scheduling = optionalMapping(value, "scheduling");
        IngressConfiguration configuration = new IngressConfiguration(
                enumValue(SessionSchedulingPolicy.class, scheduling.getOrDefault("policy", "PARALLEL")),
                enumValue(SessionGroupScope.class, scheduling.getOrDefault("groupScope", "INGRESS")),
                integer(scheduling.getOrDefault("maxParallelSessions", 64), "scheduling.maxParallelSessions"),
                integer(scheduling.getOrDefault("queueCapacity", 256), "scheduling.queueCapacity"));
        return new KuudraManifest.IngressSession(mode, "", configuration,
                sessionDependencies(optionalList(value, "dependencies"), source));
    }

    private static List<io.github.actforever.kuudra.api.session.SessionDependencyRequirement> sessionDependencies(
            List<Object> values, String source) throws IOException {
        List<io.github.actforever.kuudra.api.session.SessionDependencyRequirement> dependencies = new ArrayList<>();
        for (Object item : values) {
            Map<String, Object> dependency = mapping(item, source + ".dependencies[]");
            Map<String, Object> selector = mapping(required(dependency, "requiredSessionSelector"),
                    source + ".dependencies[].requiredSessionSelector");
            dependencies.add(new io.github.actforever.kuudra.api.session.SessionDependencyRequirement(
                    new io.github.actforever.kuudra.api.session.SessionSelector(stringMapping(selector, "matchLabels"),
                            enumValue(io.github.actforever.kuudra.api.session.SessionMatchPolicy.class,
                                    selector.getOrDefault("matchPolicy", "UNIQUE"))),
                    enumValue(io.github.actforever.kuudra.api.session.SessionTerminationPolicy.class,
                            dependency.getOrDefault("terminationPropagation", "CANCEL_DEPENDENT"))));
        }
        return List.copyOf(dependencies);
    }

    private static List<String> strings(Map<String, Object> map, String key) throws IOException {
        if (!map.containsKey(key)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : list(map.get(key))) result.add(string(value, key + "[]"));
        return List.copyOf(result);
    }

    private static List<String> abilityReferences(Map<String, Object> map, String key) throws IOException {
        List<String> values = strings(map, key);
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
        for (String value : values) {
            String[] parts = value.split("/", -1);
            if (parts.length != 2) throw new IOException(
                    "Expected namespace/name Ability reference at " + key + "[]: " + value);
            try { new KuudraManifest.ResourceId("Ability", parts[0], parts[1]); }
            catch (IllegalArgumentException error) {
                throw new IOException("Invalid Ability reference at " + key + "[]: " + value, error);
            }
            if (!unique.add(value)) throw new IOException("Duplicate value at " + key + ": " + value);
        }
        return List.copyOf(unique);
    }

    @FunctionalInterface
    private interface V2DocumentConsumer {
        void accept(ManifestDocument document, Path file, int documentIndex) throws IOException;
    }

    private static void loadManifest(ManifestDocument document, Path file, int documentIndex,
                                     Map<KuudraManifest.ResourceId, KuudraManifest.Component> components,
                                     Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows,
                                     Map<KuudraManifest.ResourceId, KuudraManifest.CoordinationPolicy> policies) throws IOException {
                String source = file + ":" + document.line("") + " (document " + documentIndex + ")";
                Map<String, Object> root = mapping(document.value(), source);
                String apiVersion = string(required(root, "apiVersion", document, "", "apiVersion: " + KuudraManifest.API_VERSION), source + ".apiVersion");
                if (!KuudraManifest.API_VERSION.equals(apiVersion)) throw new IOException("Unsupported apiVersion at " + source + ": " + apiVersion);
                String kind = string(required(root, "kind", document, "", "kind: EventSource|EventInterpreter|EventAdapter|Ingress|EventHandler|Egress|Flow|SessionCoordinationPolicy"), source + ".kind");
                Map<String, Object> metadataMap = mapping(required(root, "metadata", document, "", "metadata: {namespace: default, name: resource-name}"), source + ".metadata");
                String namespace = string(metadataMap.getOrDefault("namespace", "default"), source + ".metadata.namespace");
                String name = string(required(metadataMap, "name", document, "metadata", "metadata.name: resource-name"), source + ".metadata.name");
                String resource = kind + " " + namespace + "/" + name;
                KuudraManifest.Metadata metadata;
                try {
                    metadata = new KuudraManifest.Metadata(namespace, name,
                            stringMapping(metadataMap, "labels"), stringMapping(metadataMap, "annotations"));
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid " + resource + " metadata at " + source + ": " + invalid.getMessage(), invalid);
                }
                Map<String, Object> spec = mapping(required(root, "spec", document, "", "spec: {...}"), source + ".spec");
                try {
                    if (KuudraManifest.COMPONENT_KINDS.containsKey(kind)) {
                            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                            String type = KuudraManifest.COMPONENT_KINDS.get(kind);
                            if (spec.containsKey("type")) throw new IllegalArgumentException("spec.type has been removed; use kind: " + kind);
                            if (type.equals("event-adapter") && spec.containsKey("domain"))
                                throw new IllegalArgumentException("spec.domain has been removed; EventAdapter domain is inferred from Flow topology");
                            Map<String, Object> options = optionalMapping(spec, "options");
                            if (type.equals("event-adapter") && options.containsKey("domain"))
                                throw new IllegalArgumentException("spec.options.domain has been removed; EventAdapter domain is inferred from Flow topology");
                            KuudraManifest.Component component = new KuudraManifest.Component(id, metadata,
                                    string(required(spec, "component", document, "spec", "spec.component: plugin-namespace/plugin-id/component-name"), source + ".spec.component"),
                                    string(spec.getOrDefault("desiredState", defaultComponentState(type)), source + ".spec.desiredState").toLowerCase(java.util.Locale.ROOT),
                                    options);
                            if (components.putIfAbsent(id, component) != null) throw new IOException("Duplicate resource identity: " + id);
                    } else switch (kind) {
                        case "Flow" -> {
                            if (spec.containsKey("desiredState")) throw new IllegalArgumentException("Flow is a routing declaration and does not support spec.desiredState");
                            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                            Map<String, Object> session = optionalMapping(spec, "session");
                            io.github.actforever.kuudra.api.runtime.FlowExecutionClass executionClass = enumValue(
                                    io.github.actforever.kuudra.api.runtime.FlowExecutionClass.class,
                                    session.getOrDefault("executionClass", "DATA"));
                            Map<String, KuudraManifest.ResourceReference> imports = new LinkedHashMap<>();
                            for (Map.Entry<String, Object> entry : mapping(required(spec, "imports", document, "spec", "spec.imports: {alias: {kind: EventSource, name: resource-name}}"), source + ".spec.imports").entrySet()) {
                                Map<String, Object> reference = mapping(entry.getValue(), source + ".spec.imports." + entry.getKey());
                                imports.put(entry.getKey(), new KuudraManifest.ResourceReference(
                                        string(required(reference, "kind", document, "spec.imports." + entry.getKey(), "kind: EventSource"), "reference.kind"),
                                        string(reference.getOrDefault("namespace", namespace), "reference.namespace"),
                                        string(required(reference, "name", document, "spec.imports." + entry.getKey(), "name: resource-name"), "reference.name")));
                            }
                            List<KuudraConfig.EdgeConfig> edges = new ArrayList<>();
                            for (Object item : list(requiredForResource(spec, "edges", document, "spec",
                                    "spec.edges: [{from: source, to: ingress}]", resource, source))) {
                                Map<String, Object> edge = mapping(item, source + ".spec.edges");
                                edges.add(new KuudraConfig.EdgeConfig(string(required(edge, "from"), "edge.from"), string(required(edge, "to"), "edge.to")));
                            }
                            KuudraManifest.Flow flow = new KuudraManifest.Flow(id, metadata, executionClass, imports, edges);
                            if (flows.putIfAbsent(id, flow) != null) throw new IOException("Duplicate resource identity: " + id);
                        }
                        case "SessionCoordinationPolicy" -> {
                            if (spec.containsKey("desiredState")) throw new IllegalArgumentException("SessionCoordinationPolicy is declarative and does not support spec.desiredState");
                            KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(kind, namespace, name);
                            Map<String, Object> selector = mapping(required(spec, "selector", document, "spec",
                                    "spec.selector.matchLabels: {role: job}"), source + ".spec.selector");
                            Map<String, String> matchLabels = stringMapping(selector, "matchLabels");
                            Map<String, Object> scheduling = optionalMapping(spec, "scheduling");
                            io.github.actforever.kuudra.api.component.IngressConfiguration configuration =
                                    new io.github.actforever.kuudra.api.component.IngressConfiguration(
                                            enumValue(io.github.actforever.kuudra.api.session.SessionSchedulingPolicy.class,
                                                    scheduling.getOrDefault("policy", "PARALLEL")),
                                            enumValue(io.github.actforever.kuudra.api.session.SessionGroupScope.class,
                                                    scheduling.getOrDefault("groupScope", "INGRESS")),
                                            integer(scheduling.getOrDefault("maxParallelSessions", 64), source + ".spec.scheduling.maxParallelSessions"),
                                            integer(scheduling.getOrDefault("queueCapacity", 256), source + ".spec.scheduling.queueCapacity"));
                            List<io.github.actforever.kuudra.api.session.SessionDependencyRequirement> dependencies = new ArrayList<>();
                            for (Object item : optionalList(spec, "dependencies")) {
                                Map<String, Object> dependency = mapping(item, source + ".spec.dependencies[]");
                                Map<String, Object> requiredSelector = mapping(required(dependency, "requiredSessionSelector"), source + ".spec.dependencies[].requiredSessionSelector");
                                Map<String, String> requiredLabels = stringMapping(requiredSelector, "matchLabels");
                                dependencies.add(new io.github.actforever.kuudra.api.session.SessionDependencyRequirement(
                                        new io.github.actforever.kuudra.api.session.SessionSelector(requiredLabels,
                                                enumValue(io.github.actforever.kuudra.api.session.SessionMatchPolicy.class,
                                                        requiredSelector.getOrDefault("matchPolicy", "UNIQUE"))),
                                        enumValue(io.github.actforever.kuudra.api.session.SessionTerminationPolicy.class,
                                                dependency.getOrDefault("terminationPropagation", "CANCEL_DEPENDENT"))));
                            }
                            KuudraManifest.CoordinationPolicy policy = new KuudraManifest.CoordinationPolicy(
                                    id, metadata, matchLabels, configuration, dependencies);
                            if (policies.putIfAbsent(id, policy) != null) throw new IOException("Duplicate resource identity: " + id);
                        }
                        default -> throw new IOException("Unsupported manifest kind at " + source + ": " + kind);
                    }
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid " + resource + " at " + source + ": " + invalid.getMessage(), invalid);
                }
    }

    private static String defaultComponentState(Object type) {
        return java.util.Set.of("event-source", "event-interpreter", "event-handler").contains(type)
                ? "running" : "active";
    }
    private static List<Object> optionalList(Map<String, Object> map, String key) throws IOException {
        return map.containsKey(key) ? list(map.get(key)) : List.of();
    }
    private static int integer(Object value, String location) throws IOException {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException invalid) { throw new IOException("Expected integer at " + location + ": " + value, invalid); }
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        return Enum.valueOf(type, String.valueOf(value).replace('-', '_').toUpperCase(java.util.Locale.ROOT));
    }
    private static boolean isYaml(Path path) { String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT); return name.endsWith(".yaml") || name.endsWith(".yml"); }
    private static Map<String, String> stringMapping(Map<String, Object> map, String key) throws IOException {
        if (!map.containsKey(key)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : mapping(map.get(key), key).entrySet()) result.put(entry.getKey(), string(entry.getValue(), key + "." + entry.getKey()));
        return Map.copyOf(result);
    }

    private static Object read(Path file) throws IOException { try (Reader reader = Files.newBufferedReader(file)) { return new Yaml().load(reader); } }
    private static List<ManifestDocument> readAll(Path file) throws IOException {
        List<Object> values = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            new Yaml().loadAll(reader).forEach(values::add);
        } catch (MarkedYAMLException invalid) {
            int line = invalid.getProblemMark() == null ? 1 : invalid.getProblemMark().getLine() + 1;
            int column = invalid.getProblemMark() == null ? 1 : invalid.getProblemMark().getColumn() + 1;
            throw new IOException("Invalid YAML syntax at " + file + ":" + line + ":" + column + ": "
                    + invalid.getProblem(), invalid);
        } catch (YAMLException invalid) {
            throw new IOException("Invalid YAML syntax in " + file + ": " + invalid.getMessage(), invalid);
        }
        List<Node> nodes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) { new Yaml().composeAll(reader).forEach(nodes::add); }
        List<ManifestDocument> documents = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Map<String, Integer> lines = new LinkedHashMap<>();
            Node node = index < nodes.size() ? nodes.get(index) : null;
            if (node != null) collectLines(node, "", lines);
            documents.add(new ManifestDocument(values.get(index), lines));
        }
        return List.copyOf(documents);
    }

    private static void collectLines(Node node, String path, Map<String, Integer> lines) {
        lines.putIfAbsent(path, node.getStartMark().getLine() + 1);
        if (node instanceof MappingNode mapping) {
            mapping.getValue().forEach(tuple -> {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    String child = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                    collectLines(tuple.getValueNode(), child, lines);
                }
            });
        } else if (node instanceof SequenceNode sequence) {
            for (int index = 0; index < sequence.getValue().size(); index++) {
                collectLines(sequence.getValue().get(index), path + "[" + index + "]", lines);
            }
        }
    }
    private static void mergeMappings(Map<String, Object> target, Map<String, Object> source) throws IOException {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> && entry.getValue() instanceof Map<?, ?>) {
                Map<String, Object> nested = new LinkedHashMap<>(mapping(existing, entry.getKey()));
                mergeMappings(nested, mapping(entry.getValue(), entry.getKey()));
                target.put(entry.getKey(), nested);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }
    private static Map<String, Object> mapping(Object value, Object location) throws IOException {
        if (!(value instanceof Map<?, ?> source)) throw new IOException("Expected mapping at " + location);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) { if (!(entry.getKey() instanceof String key)) throw new IOException("Mapping key must be text at " + location); result.put(key, entry.getValue()); }
        return result;
    }
    private static Map<String, Object> optionalMapping(Map<String, Object> map, String key) throws IOException { return !map.containsKey(key) ? Map.of() : mapping(map.get(key), key); }
    private static List<Object> list(Object value) throws IOException { if (value == null) return List.of(); if (!(value instanceof List<?> list)) throw new IOException("Expected list"); return List.copyOf(list); }
    private static Object required(Map<String, Object> map, String key) throws IOException { Object value = map.get(key); if (value == null) throw new IOException("Missing required value: " + key); return value; }
    private static Object required(Map<String, Object> map, String key, ManifestDocument document,
                                   String parentPath, String example) throws IOException {
        Object value = map.get(key);
        if (value != null) return value;
        String path = parentPath.isEmpty() ? key : parentPath + "." + key;
        throw new IOException("Missing required field '" + path + "' near line " + document.line(parentPath)
                + "; expected format: " + example);
    }
    private static Object requiredForResource(Map<String, Object> map, String key, ManifestDocument document,
                                              String parentPath, String example, String resource, String source) throws IOException {
        try {
            return required(map, key, document, parentPath, example);
        } catch (IOException invalid) {
            throw new IOException("Invalid " + resource + " at " + source + ": " + invalid.getMessage(), invalid);
        }
    }
    private static String string(Object value, String location) throws IOException { if (!(value instanceof String text) || text.isBlank()) throw new IOException("Expected non-blank string at " + location); return text; }
    private static int integer(Map<String, Object> map, String key, int fallback) throws IOException { Object value = map.get(key); if (value == null) return fallback; if (value instanceof Number number) return number.intValue(); try { return Integer.parseInt(string(value, key)); } catch (NumberFormatException error) { throw new IOException("Expected integer at " + key, error); } }
    private static int nonNegativeInteger(Map<String, Object> map, String key, int fallback) throws IOException {
        int value = integer(map, key, fallback);
        if (value < 0) throw new IOException("Expected non-negative integer at " + key);
        return value;
    }
    private static <E extends Enum<E>> E enumValue(Map<String, Object> map, String key, E fallback, Class<E> type) throws IOException {
        Object value = map.get(key); if (value == null) return fallback;
        try { return Enum.valueOf(type, string(value, key).replace('-', '_').toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IOException("Unsupported value at " + key + ": " + value, error); }
    }
    private static boolean bool(Object value, boolean fallback) throws IOException { if (value == null) return fallback; if (value instanceof Boolean flag) return flag; if (value instanceof String text) return Boolean.parseBoolean(text); throw new IOException("Expected boolean"); }
    private record ManifestDocument(Object value, Map<String, Integer> lines) {
        int line(String path) { return lines.getOrDefault(path, lines.getOrDefault("", 1)); }
    }
}
