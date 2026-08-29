package io.github.actforever.kuudra.config;

import io.github.actforever.kuudra.api.session.SessionAdmissionMode;
import io.github.actforever.kuudra.api.session.SessionGroupScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KuudraV2ConfigTest {
    @TempDir Path directory;

    @Test
    void loadsResourcesAbilitiesProfilesAndTimeouts() throws Exception {
        Path home = directory.resolve("home");
        write(home.resolve("manifests/pipeline.yaml"), pipeline("JOIN", "targetIngress: admit"));
        write(home.resolve("ability-profiles/default.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: AbilityProfile
                metadata: {name: default}
                spec: {namespaces: [demo], exclude: [demo/disabled]}
                """);
        Path config = write(directory.resolve("config.yaml"), """
                home-directory: home
                ability-profiles: [default]
                runtime:
                  ability-drain-timeout-ms: 6000
                  cancel-grace-timeout-ms: 7000
                  resource-lifecycle-timeout-ms: 90000
                """);
        KuudraConfig.RuntimeConfig loaded = KuudraYamlLoader.load(config);
        assertEquals(2, loaded.deployment().resources().size());
        assertEquals(1, loaded.deployment().abilities().size());
        assertEquals(1, loaded.deployment().profiles().size());
        assertEquals(java.util.List.of("default"), loaded.abilityProfiles());
        assertEquals(6000, loaded.runtime().abilityDrainTimeoutMs());
        assertEquals(7000, loaded.runtime().cancelGraceTimeoutMs());
        assertEquals(90000, loaded.runtime().resourceLifecycleTimeoutMs());
        KuudraManifest.Ability ability = loaded.deployment().abilities().values().iterator().next();
        assertEquals("disconnect", ability.nodes().get("disconnect").handler());
        assertEquals(SessionAdmissionMode.CREATE, ability.nodes().get("admit").session().mode());
        assertEquals(SessionAdmissionMode.JOIN, ability.nodes().get("join").session().mode());
        assertEquals(SessionGroupScope.INGRESS, ability.nodes().get("admit").session().scheduling().groupScope());
    }

    @Test
    void validatesJoinStaticOptionsAndMigrationBoundary() throws Exception {
        Path badJoin = directory.resolve("bad-join/manifests");
        write(badJoin.resolve("pipeline.yaml"), pipeline("JOIN", "targetIngress: absent"));
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                badJoin, directory.resolve("bad-join/profiles"))).getMessage().contains("same Ability"));
        Path staticOptions = directory.resolve("static/manifests");
        write(staticOptions.resolve("resource.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: demo, name: network}
                spec:
                  template: actforever/network/controller
                  options: {target: '${event#pid}'}
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                staticOptions, directory.resolve("static/profiles"))).getMessage().contains("static"));
        Path legacy = directory.resolve("legacy/manifests");
        write(legacy.resolve("legacy.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: demo, name: old}
                spec: {}
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                legacy, directory.resolve("legacy/profiles"))).getMessage().contains("migrate Flow to Ability"));
    }

    @Test
    void rejectsRemovedGlobalDefaults() throws Exception {
        Path selection = write(directory.resolve("selection.yaml"), """
                home-directory: selection-home
                resource-selection: {namespace-mode: ALL}
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.load(selection))
                .getMessage().contains("ability-profiles"));
        Path coordinator = write(directory.resolve("coordinator.yaml"), """
                home-directory: coordinator-home
                runtime:
                  session-coordinator: {default-policy: PARALLEL}
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.load(coordinator))
                .getMessage().contains("CREATE Ingress"));
    }

    private static String pipeline(String joinMode, String joinExtra) {
        return """
                apiVersion: kuudra.io/v1alpha2
                kind: Ingress
                metadata: {namespace: demo, name: ingress}
                spec: {template: kuudra-official/default/plain-ingress}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: demo, name: network}
                spec:
                  template: actforever/network/network-controller
                  options: {allowElevation: true}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Ability
                metadata: {namespace: demo, name: network-control}
                spec:
                  resources:
                    ingress: {kind: Ingress, name: ingress}
                    network: {kind: Controller, name: network}
                  nodes:
                    admit:
                      resource: ingress
                      session: {mode: CREATE}
                    join:
                      resource: ingress
                      session:
                        mode: %s
                        %s
                    disconnect:
                      resource: network
                      handler: disconnect
                      arguments: {target: '${event#processAlias}'}
                  edges: [{from: admit, to: disconnect}]
                """.formatted(joinMode, joinExtra);
    }

    private static Path write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
        return file;
    }
}
