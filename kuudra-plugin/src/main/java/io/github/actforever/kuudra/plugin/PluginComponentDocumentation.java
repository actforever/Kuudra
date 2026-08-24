package io.github.actforever.kuudra.plugin;

import java.util.List;

public record PluginComponentDocumentation(String purpose, String usageExample, boolean lifecycle,
                                           List<String> lifecyclePhases,
                                           List<PluginEventDocumentation> emittedEvents) {
    public static final PluginComponentDocumentation EMPTY = new PluginComponentDocumentation("", "", false, List.of(), List.of());
    public PluginComponentDocumentation {
        purpose = purpose == null ? "" : purpose;
        usageExample = usageExample == null ? "" : usageExample;
        lifecyclePhases = List.copyOf(lifecyclePhases);
        emittedEvents = List.copyOf(emittedEvents);
    }
}
