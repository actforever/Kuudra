package io.github.actforever.kuudra.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.config.KuudraManifest;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite StateStore whose SQL mapping is isolated behind MyBatis. */
public final class SqliteResourceStateStore implements ResourceStateStore {
    private static final int CONTROL_PLANE_SCHEMA_VERSION = 2;
    private static final String PROFILE_NAMESPACE = "kuudra-system";
    private final SqlSessionFactory sessions;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private volatile boolean closed;

    public SqliteResourceStateStore(Path database) { this(database, 5_000); }

    public SqliteResourceStateStore(Path database, int busyTimeoutMs) {
        Path normalized = database.toAbsolutePath().normalize();
        if (busyTimeoutMs < 0) throw new IllegalArgumentException("busyTimeoutMs must not be negative");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setBusyTimeout(busyTimeoutMs);
        dataSource.setConfig(sqlite);
        dataSource.setUrl("jdbc:sqlite:" + normalized);
        Environment environment = new Environment("kuudra-state", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addMapper(ResourceStateMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = sessions.openSession(true)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.createSchemaMetadata();
            Integer version = mapper.schemaVersion();
            if (version == null || version != CONTROL_PLANE_SCHEMA_VERSION) {
                // `resources` is a Kuudra-owned v0.4 control-plane table. No other
                // table is dropped, so plugin-owned data in the same database survives.
                mapper.dropCoreResources();
                mapper.setSchemaVersion(CONTROL_PLANE_SCHEMA_VERSION);
            }
            mapper.createSchema();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to open StateStore " + normalized, error);
        }
    }

    @Override public synchronized void replaceDesired(KuudraManifest.Deployment deployment) {
        requireOpen();
        try (SqlSession session = sessions.openSession(false)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            Set<KuudraManifest.ResourceId> retained = new HashSet<>();
            deployment.resources().values().forEach(value -> persist(mapper, value.id(), "resource", value, retained));
            deployment.abilities().values().forEach(value -> persist(mapper, value.id(), "ability", value, retained));
            deployment.profiles().values().forEach(value -> persist(mapper,
                    new KuudraManifest.ResourceId("AbilityProfile", PROFILE_NAMESPACE, value.name()),
                    "ability-profile", value, retained));
            mapper.findAll().stream().map(SqliteResourceStateStore::id).filter(id -> !retained.contains(id))
                    .forEach(id -> mapper.delete(id.kind(), id.namespace(), id.name()));
            session.commit();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to persist desired deployment state", error);
        }
    }

    @Override public synchronized KuudraManifest.Deployment desiredDeployment() {
        requireOpen();
        Map<KuudraManifest.ResourceId, KuudraManifest.Resource> resources = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.Ability> abilities = new LinkedHashMap<>();
        Map<String, KuudraManifest.AbilityProfile> profiles = new LinkedHashMap<>();
        try (SqlSession session = sessions.openSession()) {
            for (ResourceStateRow row : session.getMapper(ResourceStateMapper.class).findAll()) {
                switch (row.getResourceType()) {
                    case "resource" -> {
                        KuudraManifest.Resource value = json.readValue(row.getDesiredJson(), KuudraManifest.Resource.class);
                        resources.put(value.id(), value);
                    }
                    case "ability" -> {
                        KuudraManifest.Ability value = json.readValue(row.getDesiredJson(), KuudraManifest.Ability.class);
                        abilities.put(value.id(), value);
                    }
                    case "ability-profile" -> {
                        KuudraManifest.AbilityProfile value = json.readValue(row.getDesiredJson(), KuudraManifest.AbilityProfile.class);
                        profiles.put(value.name(), value);
                    }
                    default -> throw new KuudraException("Unknown v0.5 persisted resource type: " + row.getResourceType());
                }
            }
            return new KuudraManifest.Deployment(resources, abilities, profiles);
        } catch (Exception error) {
            throw KuudraException.wrap("Failed to read desired deployment state", error);
        }
    }

    @Override public synchronized void replaceDesired(KuudraManifest.Resources resources) {
        requireOpen();
        try (SqlSession session = sessions.openSession(false)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            Set<KuudraManifest.ResourceId> retained = new HashSet<>();
            resources.components().values().forEach(value -> persist(mapper, value.id(), "component", value, retained));
            resources.flows().values().forEach(value -> persist(mapper, value.id(), "flow", value, retained));
            resources.coordinationPolicies().values().forEach(value -> persist(mapper, value.id(), "coordination-policy", value, retained));
            mapper.findAll().stream().map(SqliteResourceStateStore::id).filter(id -> !retained.contains(id))
                    .forEach(id -> mapper.delete(id.kind(), id.namespace(), id.name()));
            session.commit();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to persist desired resource state", error);
        }
    }

    private void persist(ResourceStateMapper mapper, KuudraManifest.ResourceId id, String type,
                         Object value, Set<KuudraManifest.ResourceId> retained) {
        try {
            mapper.upsert(id.kind(), id.namespace(), id.name(), type,
                    json.writeValueAsString(value), Instant.now().toString());
            retained.add(id);
        } catch (Exception error) {
            throw KuudraException.wrap("Failed to serialize desired resource " + id, error);
        }
    }

    @Override public synchronized KuudraManifest.Resources desiredResources() {
        requireOpen();
        Map<KuudraManifest.ResourceId, KuudraManifest.Component> components = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.Flow> flows = new LinkedHashMap<>();
        Map<KuudraManifest.ResourceId, KuudraManifest.CoordinationPolicy> policies = new LinkedHashMap<>();
        try (SqlSession session = sessions.openSession()) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            for (ResourceStateRow row : mapper.findAll()) {
                if ("component".equals(row.getResourceType())) {
                    KuudraManifest.Component value = json.readValue(row.getDesiredJson(), KuudraManifest.Component.class);
                    components.put(value.id(), value);
                } else if ("flow".equals(row.getResourceType())) {
                    KuudraManifest.Flow value = json.readValue(row.getDesiredJson(), KuudraManifest.Flow.class);
                    flows.put(value.id(), value);
                } else if ("coordination-policy".equals(row.getResourceType())) {
                    KuudraManifest.CoordinationPolicy value = json.readValue(row.getDesiredJson(), KuudraManifest.CoordinationPolicy.class);
                    policies.put(value.id(), value);
                } else {
                    throw new KuudraException("Unknown persisted resource type: " + row.getResourceType());
                }
            }
            return new KuudraManifest.Resources(components, flows, policies);
        } catch (Exception error) {
            throw KuudraException.wrap("Failed to read desired resource state", error);
        }
    }

    @Override public synchronized List<ResourceState> states() {
        requireOpen();
        try (SqlSession session = sessions.openSession()) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            return mapper.findAll().stream()
                    .filter(row -> !"ability-profile".equals(row.getResourceType()))
                    .map(row -> new ResourceState(id(row), row.getGeneration(), row.getObservedGeneration(),
                            row.getPhase(), row.getMessage())).toList();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to query resource states", error);
        }
    }

    @Override public synchronized void markAllObserved(String phase, String message) {
        requireOpen();
        try (SqlSession session = sessions.openSession(false)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.markAllObserved(phase, message, Instant.now().toString());
            session.commit();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to update observed resource state", error);
        }
    }

    @Override public synchronized void markObserved(KuudraManifest.ResourceId id, String phase, String message) {
        updateOne(id, mapper -> mapper.markObserved(id.kind(), id.namespace(), id.name(), phase, message, Instant.now().toString()));
    }

    @Override public synchronized void markFailed(KuudraManifest.ResourceId id, String message) {
        updateOne(id, mapper -> mapper.markFailed(id.kind(), id.namespace(), id.name(), message, Instant.now().toString()));
    }

    private void updateOne(KuudraManifest.ResourceId id, java.util.function.Consumer<ResourceStateMapper> update) {
        requireOpen();
        try (SqlSession session = sessions.openSession(false)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            update.accept(mapper); session.commit();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to update observed resource " + id, error);
        }
    }

    private static KuudraManifest.ResourceId id(ResourceStateRow row) {
        return new KuudraManifest.ResourceId(row.getKind(), row.getNamespace(), row.getName());
    }

    private void requireOpen() {
        if (closed) throw new KuudraException("StateStore is closed");
    }

    /** MyBatis owns short-lived sessions; SQLiteDataSource itself has no pool requiring shutdown. */
    @Override public synchronized void close() { closed = true; }
}
