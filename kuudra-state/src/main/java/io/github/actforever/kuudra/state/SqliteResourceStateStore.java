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

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite StateStore whose SQL mapping is isolated behind MyBatis. */
public final class SqliteResourceStateStore implements ResourceStateStore {
    private final SqlSessionFactory sessions;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    private volatile boolean closed;

    public SqliteResourceStateStore(Path database) {
        Path normalized = database.toAbsolutePath().normalize();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + normalized);
        Environment environment = new Environment("kuudra-state", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addMapper(ResourceStateMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = sessions.openSession(true)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.configureConnection();
            mapper.createSchema();
        } catch (RuntimeException error) {
            throw KuudraException.wrap("Failed to open StateStore " + normalized, error);
        }
    }

    @Override public synchronized void replaceDesired(KuudraManifest.Resources resources) {
        requireOpen();
        try (SqlSession session = sessions.openSession(false)) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.configureConnection();
            Set<KuudraManifest.ResourceId> retained = new HashSet<>();
            resources.components().values().forEach(value -> persist(mapper, value.id(), "component", value, retained));
            resources.flows().values().forEach(value -> persist(mapper, value.id(), "flow", value, retained));
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
        try (SqlSession session = sessions.openSession()) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.configureConnection();
            for (ResourceStateRow row : mapper.findAll()) {
                if ("component".equals(row.getResourceType())) {
                    KuudraManifest.Component value = json.readValue(row.getDesiredJson(), KuudraManifest.Component.class);
                    components.put(value.id(), value);
                } else if ("flow".equals(row.getResourceType())) {
                    KuudraManifest.Flow value = json.readValue(row.getDesiredJson(), KuudraManifest.Flow.class);
                    flows.put(value.id(), value);
                } else {
                    throw new KuudraException("Unknown persisted resource type: " + row.getResourceType());
                }
            }
            return new KuudraManifest.Resources(components, flows);
        } catch (Exception error) {
            throw KuudraException.wrap("Failed to read desired resource state", error);
        }
    }

    @Override public synchronized List<ResourceState> states() {
        requireOpen();
        try (SqlSession session = sessions.openSession()) {
            ResourceStateMapper mapper = session.getMapper(ResourceStateMapper.class);
            mapper.configureConnection();
            return mapper.findAll().stream()
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
            mapper.configureConnection();
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
            mapper.configureConnection(); update.accept(mapper); session.commit();
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
