package io.github.actforever.kuudra.app;

import io.github.actforever.kuudra.api.event.KuudraEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

class AbilityAppTest {
    @TempDir Path directory;

    @Test
    void reconcilesProfileClaimsRoutesNamedHandlerAndHonorsDirectOverride() throws Exception {
        TestAbilityPlugin.NetworkController.reset();
        Path home = directory.resolve("home");
        pluginJar(home.resolve("plugins/ability-test.jar"));
        write(home.resolve("manifests/network.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: EventSource
                metadata: {namespace: input, name: source}
                spec: {template: test/ability-test/bound-source}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Ingress
                metadata: {namespace: shared, name: ingress}
                spec: {template: test/ability-test/group-ingress}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: operations, name: network}
                spec: {template: test/ability-test/network-controller}
                ---
                apiVersion: kuudra.io/v1alpha2
                kind: Controller
                metadata: {namespace: operations, name: unused}
                spec: {template: test/ability-test/network-controller}
                """);
        write(home.resolve("abilities/network.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: Ability
                metadata: {namespace: demo, name: disconnect}
                spec:
                  resources:
                    source: EventSource/input/source
                    unused: {kind: Controller, namespace: operations, name: unused}
                  nodes:
                    source: {resource: source}
                    admit:
                      resource: {kind: Ingress, namespace: shared, name: ingress}
                      arguments: {group: game}
                      session: {mode: CREATE}
                    disconnect:
                      resource: Controller/operations/network
                      handler: disconnect
                      arguments: {alias: '${event#alias}'}
                  edges: [{from: source, to: admit}, {from: admit, to: disconnect}]
                """);
        write(home.resolve("abilities/profiles/default.yaml"), """
                apiVersion: kuudra.io/v1alpha2
                kind: AbilityProfile
                metadata: {name: default}
                spec: {abilities: [demo/disconnect]}
                """);
        Path config = write(directory.resolve("config.yaml"), """
                home-directory: home
                ability-profiles: [default]
                reconciliation: {enabled: true, interval-ms: 10}
                logging: {console-enabled: false, file-enabled: false}
                """);

        try (KuudraApp app = KuudraApp.createConfigured(config)) {
            assertEquals("ENABLED", app.ability("demo", "disconnect").orElseThrow().state());
            assertEquals(4, app.manifestResources().size());
            assertEquals(3, app.manifestResources().stream()
                    .filter(resource -> resource.state().equals("RUNNING")).count());
            assertEquals("DESTROYED", app.manifestResources().stream()
                    .filter(resource -> resource.id().equals("Controller/operations/unused"))
                    .findFirst().orElseThrow().state());
            assertEquals("disconnect", app.resourceTemplates().stream()
                    .filter(template -> template.kind().equals("Controller")).findFirst().orElseThrow()
                    .handlers().get(0).name());
            assertTrue(app.publish("demo/disconnect", "admit", KuudraEvent.of("network", Map.of("alias", "gta"))));
            await(() -> TestAbilityPlugin.NetworkController.CALLS.get() == 1);
            assertEquals("gta", TestAbilityPlugin.NetworkController.alias);
            assertEquals(1, TestAbilityPlugin.NetworkController.STARTS.get());

            app.controlAbility("demo", "disconnect", "pause").toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("PAUSED", app.ability("demo", "disconnect").orElseThrow().state());
            assertFalse(app.publish("demo/disconnect", "admit", KuudraEvent.of("network", Map.of("alias", "ignored"))));

            app.controlAbility("demo", "disconnect", "disable").toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("DISABLED", app.ability("demo", "disconnect").orElseThrow().state());
            assertTrue(app.manifestResources().stream().allMatch(resource -> resource.state().equals("DESTROYED")));
            assertEquals(1, TestAbilityPlugin.NetworkController.DESTROYS.get());

            var events = new CopyOnWriteArrayList<String>();
            try (AutoCloseable ignored = app.systemEvents().subscribe(event -> events.add(event.type()))) {
                app.restart();
                Thread.sleep(100);
                assertFalse(events.contains("reconciliation.loop.failed"),
                        "v1alpha2 deployments must not enter the legacy v1alpha1 reconciler");
            }
        }
    }

    private void pluginJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            add(output, "META-INF/kuudra-plugin/metadata.toml", """
                    id = "ability-test"
                    namespace = "test"
                    version = "1.0.0"
                    entrypoint = "io.github.actforever.kuudra.app.TestAbilityPlugin"
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (Class<?> type : java.util.List.of(TestAbilityPlugin.class,
                    TestAbilityPlugin.BoundSource.class, TestAbilityPlugin.GroupIngress.class,
                    TestAbilityPlugin.NetworkController.class)) {
                String name = type.getName().replace('.', '/') + ".class";
                try (var input = type.getClassLoader().getResourceAsStream(name)) {
                    add(output, name, java.util.Objects.requireNonNull(input).readAllBytes());
                }
            }
        }
    }

    private static void add(JarOutputStream output, String name, byte[] value) throws IOException {
        output.putNextEntry(new JarEntry(name)); output.write(value); output.closeEntry();
    }
    private static Path write(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent()); Files.writeString(file, value); return file;
    }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.call());
    }
}
