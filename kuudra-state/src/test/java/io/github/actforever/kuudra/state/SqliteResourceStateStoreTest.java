package io.github.actforever.kuudra.state;

import io.github.actforever.kuudra.config.KuudraManifest;
import io.github.actforever.kuudra.config.KuudraYamlLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class SqliteResourceStateStoreTest {
    @TempDir Path directory;

    @Test
    void persistsV1Alpha2DeploymentAndObservedGenerations() throws Exception {
        Path manifests = directory.resolve("manifests");
        Path abilities = directory.resolve("abilities");
        Path profiles = abilities.resolve("profiles");
        Files.createDirectories(manifests);
        Files.createDirectories(abilities);
        Files.createDirectories(profiles);
        Files.writeString(manifests.resolve("resource.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: demo, name: network}
                spec: {template: test/native/network}
                """);
        Files.writeString(abilities.resolve("ability.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: Ability
                metadata: {namespace: demo, name: disconnect}
                spec:
                  resources:
                    network: Controller/demo/network
                  nodes:
                    disconnect: {resource: network, handler: disconnect}
                  edges: []
                """);
        Files.writeString(profiles.resolve("default.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: AbilityProfile
                metadata: {name: default}
                spec: {abilities: [demo/disconnect]}
                """);
        KuudraManifest.Deployment deployment = KuudraYamlLoader.loadDeployment(manifests, abilities, profiles);
        Path database = directory.resolve("kuudra.db");

        try (var store = new SqliteResourceStateStore(database)) {
            store.replaceDesired(deployment);
            assertEquals(deployment, store.desiredDeployment());
            assertEquals(2, store.states().size());
            assertTrue(store.states().stream().allMatch(state -> state.generation() == 1 && state.observedGeneration() == 0));
            store.markAllObserved("READY", "ok");
            assertTrue(store.states().stream().allMatch(state -> state.observedGeneration() == 1 && state.phase().equals("READY")));
            store.replaceDesired(deployment);
            assertTrue(store.states().stream().allMatch(state -> state.generation() == 1));
        }
        try (var reopened = new SqliteResourceStateStore(database)) {
            assertEquals(1, reopened.desiredDeployment().resources().size());
            assertEquals(1, reopened.desiredDeployment().abilities().size());
            assertEquals(1, reopened.desiredDeployment().profiles().size());
        }
    }

    @Test
    void rebuildsOnlyKuudraCoreTablesWhenMigratingV04Database() throws Exception {
        Path database = directory.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE resources(kind TEXT)");
            connection.createStatement().execute("CREATE TABLE plugin_state(value TEXT)");
            connection.createStatement().execute("INSERT INTO plugin_state VALUES('preserved')");
        }
        try (var ignored = new SqliteResourceStateStore(database);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var rows = connection.createStatement().executeQuery("SELECT value FROM plugin_state")) {
            assertTrue(rows.next());
            assertEquals("preserved", rows.getString(1));
        }
    }
}
