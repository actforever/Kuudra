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
        write(home.resolve("manifests/resources.yaml"), resources());
        write(home.resolve("abilities/pipeline.yaml"), pipeline("JOIN", "targetIngress: admit"));
        write(home.resolve("abilities/profiles/default.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: AbilityProfile
                metadata: {name: default}
                spec: {namespaces: [default], exclude: [default/disabled]}
                """);
        Path config = write(directory.resolve("config.yaml"), """
                home-directory: home
                ability-profiles: [default]
                abilities: [default/network-control]
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
        assertEquals(java.util.List.of("default/network-control"), loaded.abilities());
        assertEquals(6000, loaded.runtime().abilityDrainTimeoutMs());
        assertEquals(7000, loaded.runtime().cancelGraceTimeoutMs());
        assertEquals(90000, loaded.runtime().resourceLifecycleTimeoutMs());
        KuudraManifest.Ability ability = loaded.deployment().abilities().values().iterator().next();
        assertEquals("disconnect", ability.nodes().get("disconnect").handler());
        assertEquals(SessionAdmissionMode.CREATE, ability.nodes().get("admit").session().mode());
        assertEquals(SessionAdmissionMode.JOIN, ability.nodes().get("join").session().mode());
        assertEquals(SessionGroupScope.INGRESS, ability.nodes().get("admit").session().scheduling().groupScope());
        assertEquals("Ingress/shared/ingress", ability.nodes().get("admit").resource().canonicalName());
        assertEquals("Controller/operations/network", ability.nodes().get("disconnect").resource().canonicalName());
        assertEquals("default", ability.id().namespace());
    }

    @Test
    void validatesJoinStaticOptionsAndMigrationBoundary() throws Exception {
        Path badJoin = directory.resolve("bad-join");
        write(badJoin.resolve("manifests/resources.yaml"), resources());
        write(badJoin.resolve("abilities/pipeline.yaml"), pipeline("JOIN", "targetIngress: absent"));
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                badJoin.resolve("manifests"), badJoin.resolve("abilities"),
                badJoin.resolve("abilities/profiles"))).getMessage().contains("same Ability"));
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
                staticOptions, directory.resolve("static/abilities"),
                directory.resolve("static/abilities/profiles"))).getMessage().contains("static"));
        Path legacy = directory.resolve("legacy/manifests");
        write(legacy.resolve("legacy.yaml"), """
                apiVersion: kuudra.io/v1alpha1
                kind: Flow
                metadata: {namespace: demo, name: old}
                spec: {}
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                legacy, directory.resolve("legacy/abilities"),
                directory.resolve("legacy/abilities/profiles"))).getMessage().contains("Only Resource kinds"));
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

    @Test
    void rejectsInvalidAndDuplicateConfiguredAbilities() throws Exception {
        Path invalid = write(directory.resolve("invalid-ability.yaml"), """
                home-directory: invalid-home
                abilities: [missing-namespace]
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.load(invalid))
                .getMessage().contains("namespace/name"));

        Path duplicate = write(directory.resolve("duplicate-ability.yaml"), """
                home-directory: duplicate-home
                abilities: [demo/notify, demo/notify]
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.load(duplicate))
                .getMessage().contains("Duplicate"));
    }

    @Test
    void enforcesDirectoryBoundariesAndCompleteResourceReferences() throws Exception {
        Path misplaced = directory.resolve("misplaced");
        write(misplaced.resolve("manifests/ability.yaml"), pipeline("JOIN", "targetIngress: admit"));
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                misplaced.resolve("manifests"), misplaced.resolve("abilities"),
                misplaced.resolve("abilities/profiles"))).getMessage().contains("must be stored under"));

        Path incomplete = directory.resolve("incomplete");
        write(incomplete.resolve("manifests/resources.yaml"), resources());
        write(incomplete.resolve("abilities/ability.yaml"), pipeline("JOIN", "targetIngress: admit")
                .replace("Ingress/shared/ingress", "Ingress/ingress"));
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.loadDeployment(
                incomplete.resolve("manifests"), incomplete.resolve("abilities"),
                incomplete.resolve("abilities/profiles"))).getMessage().contains("kind/namespace/name"));

        Path legacyProfiles = directory.resolve("legacy-profile-home");
        write(legacyProfiles.resolve("ability-profiles/default.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: AbilityProfile
                metadata: {name: default}
                spec: {}
                """);
        Path config = write(directory.resolve("legacy-profile-config.yaml"), """
                home-directory: legacy-profile-home
                """);
        assertTrue(assertThrows(IOException.class, () -> KuudraYamlLoader.load(config))
                .getMessage().contains("has moved"));
    }

    private static String pipeline(String joinMode, String joinExtra) {
        return """
                apiVersion: kuudra.io/v1alpha2
                kind: Ability
                metadata: {name: network-control}
                spec:
                  resources:
                    ingress: Ingress/shared/ingress
                    network: {kind: Controller, namespace: operations, name: network}
                    unused: Controller/operations/network
                  nodes:
                    admit:
                      resource: ingress
                      session: {mode: CREATE}
                    join:
                      resource: {kind: Ingress, namespace: shared, name: ingress}
                      session:
                        mode: %s
                        %s
                    disconnect:
                      resource: Controller/operations/network
                      handler: disconnect
                      arguments: {target: '${event#processAlias}'}
                  edges: [{from: admit, to: disconnect}]
                """.formatted(joinMode, joinExtra);
    }

    private static String resources() {
        return """
                apiVersion: kuudra.io/v1alpha2
                kind: Ingress
                metadata: {namespace: shared, name: ingress}
                spec: {template: kuudra-official/default/plain-ingress}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: operations, name: network}
                spec:
                  template: actforever/network/network-controller
                  options: {allowElevation: true}
                """;
    }

    private static Path write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
        return file;
    }
}
