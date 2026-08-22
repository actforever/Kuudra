package io.github.actforever.kuudra.plugin;

import java.util.List;

/** Registry for plugin-owned long-lived resources such as hooks and schedulers. */
public interface PluginResourceRegistry {
    void register(String name, AutoCloseable resource);

    List<String> names();
}
