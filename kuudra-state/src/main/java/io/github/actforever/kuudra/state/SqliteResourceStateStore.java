package io.github.actforever.kuudra.state;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.actforever.kuudra.api.KuudraException;
import io.github.actforever.kuudra.config.KuudraManifest;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQLite implementation used as the App reconciliation source of truth. */
public final class SqliteResourceStateStore implements ResourceStateStore {
    private final Connection connection;
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    public SqliteResourceStateStore(Path database) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("CREATE TABLE IF NOT EXISTS resources (kind TEXT NOT NULL, namespace TEXT NOT NULL, name TEXT NOT NULL, resource_type TEXT NOT NULL, generation INTEGER NOT NULL, desired_json TEXT NOT NULL, observed_generation INTEGER NOT NULL DEFAULT 0, phase TEXT NOT NULL, message TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(kind, namespace, name))");
            }
        } catch (SQLException error) { throw KuudraException.wrap("Failed to open StateStore " + database, error); }
    }

    @Override public synchronized void replaceDesired(KuudraManifest.Resources resources) {
        try {
            connection.setAutoCommit(false);
            Set<KuudraManifest.ResourceId> retained = new HashSet<>();
            for (KuudraManifest.Component value : resources.components().values()) { upsert(value.id(), "component", json.writeValueAsString(value)); retained.add(value.id()); }
            for (KuudraManifest.Flow value : resources.flows().values()) { upsert(value.id(), "flow", json.writeValueAsString(value)); retained.add(value.id()); }
            try (PreparedStatement all = connection.prepareStatement("SELECT kind, namespace, name FROM resources"); ResultSet rows = all.executeQuery();
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM resources WHERE kind=? AND namespace=? AND name=?")) {
                while (rows.next()) {
                    KuudraManifest.ResourceId id = new KuudraManifest.ResourceId(rows.getString(1), rows.getString(2), rows.getString(3));
                    if (!retained.contains(id)) { bindId(delete, id); delete.addBatch(); }
                }
                delete.executeBatch();
            }
            connection.commit();
        } catch (Exception error) { rollback(error); throw KuudraException.wrap("Failed to persist desired resource state", error); }
        finally { try { connection.setAutoCommit(true); } catch (SQLException ignored) { } }
    }

    private void upsert(KuudraManifest.ResourceId id, String type, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO resources(kind,namespace,name,resource_type,generation,desired_json,observed_generation,phase,message,updated_at) VALUES(?,?,?,?,1,?,0,'PENDING','',?) ON CONFLICT(kind,namespace,name) DO UPDATE SET resource_type=excluded.resource_type, generation=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.generation ELSE resources.generation+1 END, desired_json=excluded.desired_json, phase=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.phase ELSE 'PENDING' END, message=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.message ELSE '' END, updated_at=excluded.updated_at")) {
            statement.setString(1,id.kind()); statement.setString(2,id.namespace()); statement.setString(3,id.name()); statement.setString(4,type); statement.setString(5,payload); statement.setString(6, Instant.now().toString()); statement.executeUpdate();
        }
    }

    @Override public synchronized KuudraManifest.Resources desiredResources() {
        Map<KuudraManifest.ResourceId,KuudraManifest.Component> components=new LinkedHashMap<>(); Map<KuudraManifest.ResourceId,KuudraManifest.Flow> flows=new LinkedHashMap<>();
        try (Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery("SELECT resource_type, desired_json FROM resources ORDER BY namespace, kind, name")) {
            while(rows.next()) if(rows.getString(1).equals("component")){var value=json.readValue(rows.getString(2),KuudraManifest.Component.class);components.put(value.id(),value);}else{var value=json.readValue(rows.getString(2),KuudraManifest.Flow.class);flows.put(value.id(),value);}
            return new KuudraManifest.Resources(components,flows);
        } catch(Exception error){throw KuudraException.wrap("Failed to read desired resource state",error);}
    }

    @Override public synchronized List<ResourceState> states(){List<ResourceState> result=new ArrayList<>();try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT kind,namespace,name,generation,observed_generation,phase,message FROM resources ORDER BY namespace,kind,name")){while(r.next())result.add(new ResourceState(new KuudraManifest.ResourceId(r.getString(1),r.getString(2),r.getString(3)),r.getLong(4),r.getLong(5),r.getString(6),r.getString(7)));return List.copyOf(result);}catch(SQLException e){throw KuudraException.wrap("Failed to query resource states",e);}}
    @Override public synchronized void markAllObserved(String phase,String message){try(PreparedStatement s=connection.prepareStatement("UPDATE resources SET observed_generation=generation,phase=?,message=?,updated_at=?")){s.setString(1,phase);s.setString(2,message);s.setString(3,Instant.now().toString());s.executeUpdate();}catch(SQLException e){throw KuudraException.wrap("Failed to update observed resource state",e);}}
    private static void bindId(PreparedStatement statement,KuudraManifest.ResourceId id)throws SQLException{statement.setString(1,id.kind());statement.setString(2,id.namespace());statement.setString(3,id.name());}
    private void rollback(Exception original){try{connection.rollback();}catch(SQLException rollback){original.addSuppressed(rollback);}}
    @Override public synchronized void close(){try{connection.close();}catch(SQLException e){throw KuudraException.wrap("Failed to close StateStore",e);}}
}
