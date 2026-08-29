package io.github.actforever.kuudra.state;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** SQL mapping for the resource StateStore. Transaction ownership remains in the store. */
interface ResourceStateMapper {
    @Update("CREATE TABLE IF NOT EXISTS kuudra_schema (name TEXT PRIMARY KEY, version INTEGER NOT NULL)")
    void createSchemaMetadata();

    @Select("SELECT version FROM kuudra_schema WHERE name='control-plane'")
    Integer schemaVersion();

    @Insert("INSERT INTO kuudra_schema(name,version) VALUES('control-plane',#{version}) " +
            "ON CONFLICT(name) DO UPDATE SET version=excluded.version")
    void setSchemaVersion(@Param("version") int version);

    @Update("DROP TABLE IF EXISTS resources")
    void dropCoreResources();

    @Update("""
            CREATE TABLE IF NOT EXISTS resources (
              kind TEXT NOT NULL, namespace TEXT NOT NULL, name TEXT NOT NULL,
              resource_type TEXT NOT NULL, generation INTEGER NOT NULL,
              desired_json TEXT NOT NULL, observed_generation INTEGER NOT NULL DEFAULT 0,
              phase TEXT NOT NULL, message TEXT NOT NULL, updated_at TEXT NOT NULL,
              PRIMARY KEY(kind, namespace, name))
            """)
    void createSchema();

    @Insert("""
            INSERT INTO resources(kind,namespace,name,resource_type,generation,desired_json,
              observed_generation,phase,message,updated_at)
            VALUES(#{kind},#{namespace},#{name},#{resourceType},1,#{desiredJson},0,'PENDING','',#{updatedAt})
            ON CONFLICT(kind,namespace,name) DO UPDATE SET
              resource_type=excluded.resource_type,
              generation=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.generation ELSE resources.generation+1 END,
              desired_json=excluded.desired_json,
              phase=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.phase ELSE 'PENDING' END,
              message=CASE WHEN resources.desired_json=excluded.desired_json THEN resources.message ELSE '' END,
              updated_at=excluded.updated_at
            """)
    void upsert(@Param("kind") String kind, @Param("namespace") String namespace,
                @Param("name") String name, @Param("resourceType") String resourceType,
                @Param("desiredJson") String desiredJson, @Param("updatedAt") String updatedAt);

    @Select("SELECT kind,namespace,name,resource_type,desired_json,generation,observed_generation,phase,message FROM resources ORDER BY namespace,kind,name")
    @Results(id="resourceStateRow", value={
            @Result(column="resource_type", property="resourceType"),
            @Result(column="desired_json", property="desiredJson"),
            @Result(column="observed_generation", property="observedGeneration")})
    List<ResourceStateRow> findAll();

    @Delete("DELETE FROM resources WHERE kind=#{kind} AND namespace=#{namespace} AND name=#{name}")
    void delete(@Param("kind") String kind, @Param("namespace") String namespace, @Param("name") String name);

    @Update("UPDATE resources SET observed_generation=generation,phase=#{phase},message=#{message},updated_at=#{updatedAt}")
    void markAllObserved(@Param("phase") String phase, @Param("message") String message,
                         @Param("updatedAt") String updatedAt);

    @Update("UPDATE resources SET observed_generation=generation,phase=#{phase},message=#{message},updated_at=#{updatedAt} WHERE kind=#{kind} AND namespace=#{namespace} AND name=#{name}")
    void markObserved(@Param("kind") String kind, @Param("namespace") String namespace,
                      @Param("name") String name, @Param("phase") String phase,
                      @Param("message") String message, @Param("updatedAt") String updatedAt);

    @Update("UPDATE resources SET phase='FAILED',message=#{message},updated_at=#{updatedAt} WHERE kind=#{kind} AND namespace=#{namespace} AND name=#{name}")
    void markFailed(@Param("kind") String kind, @Param("namespace") String namespace,
                    @Param("name") String name, @Param("message") String message,
                    @Param("updatedAt") String updatedAt);
}
