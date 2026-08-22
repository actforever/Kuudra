package io.github.actforever.kuudra.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Dependency-aware lifecycle manager for already loaded plugins.
 *
 * <p>Fat-JAR discovery and isolated ClassLoaders stay outside this first
 * kernel; this class deliberately owns only ordering, per-plugin homes and
 * lifecycle failure visibility.</p>
 */
public final class DefaultPluginManager implements AutoCloseable {
    private final Path pluginsHome;
    private final Map<String, KuudraPlugin> plugins = new LinkedHashMap<>();
    private final Map<String, PluginState> states = new LinkedHashMap<>();
    private List<String> startedOrder = List.of();

    public DefaultPluginManager(Path pluginsHome) {
        this.pluginsHome = Objects.requireNonNull(pluginsHome, "pluginsHome").toAbsolutePath().normalize();
    }

    public synchronized void register(KuudraPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = plugin.descriptor();
        if (!descriptor.id().equals(plugin.id())) {
            throw new IllegalArgumentException("Plugin id and descriptor id must match");
        }
        if (plugins.containsKey(plugin.id())) {
            throw new IllegalArgumentException("Plugin already registered: " + plugin.id());
        }
        plugins.put(plugin.id(), plugin);
        states.put(plugin.id(), PluginState.REGISTERED);
    }

    public synchronized PluginState state(String pluginId) {
        PluginState state = states.get(pluginId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown plugin: " + pluginId);
        }
        return state;
    }

    public synchronized Map<String, PluginState> states() {
        return Map.copyOf(states);
    }

    public CompletionStage<Void> startAll() {
        final List<String> order;
        synchronized (this) {
            order = dependencyOrder();
        }
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (String pluginId : order) {
            chain = chain.thenCompose(ignored -> initializeAndStart(pluginId));
        }
        return chain.thenRun(() -> {
            synchronized (this) {
                startedOrder = List.copyOf(order);
            }
        });
    }

    public CompletionStage<Void> stopAll() {
        final List<String> reverse;
        synchronized (this) {
            reverse = new ArrayList<>(startedOrder);
        }
        Collections.reverse(reverse);
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (String pluginId : reverse) {
            chain = chain.thenCompose(ignored -> stopAndDestroy(pluginId));
        }
        return chain.thenRun(() -> {
            synchronized (this) {
                startedOrder = List.of();
            }
        });
    }

    private CompletionStage<Void> initializeAndStart(String pluginId) {
        final KuudraPlugin plugin;
        final Path home;
        synchronized (this) {
            plugin = plugins.get(pluginId);
            if (states.get(pluginId) == PluginState.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
            home = pluginsHome.resolve(pluginId).normalize();
            if (!home.startsWith(pluginsHome)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid plugin id path: " + pluginId));
            }
        }
        try {
            Files.createDirectories(home);
        } catch (IOException exception) {
            markFailed(pluginId);
            return CompletableFuture.failedFuture(exception);
        }
        return invoke(plugin, current -> current.initialize(new PluginContext(pluginId, home)))
                .thenRun(() -> mark(pluginId, PluginState.INITIALIZED))
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::start))
                .thenRun(() -> mark(pluginId, PluginState.ACTIVE))
                .exceptionallyCompose(error -> failed(pluginId, error));
    }

    private CompletionStage<Void> stopAndDestroy(String pluginId) {
        final KuudraPlugin plugin;
        synchronized (this) {
            plugin = plugins.get(pluginId);
            if (states.get(pluginId) != PluginState.ACTIVE) {
                return CompletableFuture.completedFuture(null);
            }
        }
        return invoke(plugin, KuudraPlugin::stop)
                .thenCompose(ignored -> invoke(plugin, KuudraPlugin::destroy))
                .thenRun(() -> mark(pluginId, PluginState.STOPPED))
                .exceptionallyCompose(error -> failed(pluginId, error));
    }

    private CompletionStage<Void> invoke(KuudraPlugin plugin, Function<KuudraPlugin, CompletionStage<Void>> operation) {
        try {
            CompletionStage<Void> stage = operation.apply(plugin);
            return stage == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Plugin lifecycle operation returned null"))
                    : stage;
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private synchronized void mark(String pluginId, PluginState state) {
        states.put(pluginId, state);
    }

    private synchronized void markFailed(String pluginId) {
        states.put(pluginId, PluginState.FAILED);
    }

    private CompletionStage<Void> failed(String pluginId, Throwable error) {
        markFailed(pluginId);
        return CompletableFuture.failedFuture(error);
    }

    private List<String> dependencyOrder() {
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String pluginId : plugins.keySet()) {
            visit(pluginId, visited, visiting, order);
        }
        return order;
    }

    private void visit(String pluginId, Set<String> visited, Set<String> visiting, Collection<String> order) {
        if (visited.contains(pluginId)) {
            return;
        }
        if (!visiting.add(pluginId)) {
            throw new IllegalStateException("Plugin dependency cycle: " + visiting);
        }
        KuudraPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            throw new IllegalStateException("Required plugin is not registered: " + pluginId);
        }
        for (String dependency : plugin.descriptor().requires()) {
            visit(dependency, visited, visiting, order);
        }
        visiting.remove(pluginId);
        visited.add(pluginId);
        order.add(pluginId);
    }

    @Override
    public void close() {
        stopAll().toCompletableFuture().join();
    }
}
