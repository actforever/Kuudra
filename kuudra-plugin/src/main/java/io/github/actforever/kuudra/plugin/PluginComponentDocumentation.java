package io.github.actforever.kuudra.plugin;

import java.util.List;

public record PluginComponentDocumentation(String purpose, String usageExample, boolean lifecycle,
                                           List<String> lifecyclePhases,
                                           List<String> supportedDesiredStates,
                                           List<PluginConfigurationDocumentation> configuration,
                                           List<PluginEventDocumentation> emittedEvents) {
    public static final PluginComponentDocumentation EMPTY = new PluginComponentDocumentation(
            "", "", false, List.of(), List.of("ACTIVE", "INACTIVE"), List.of(), List.of());
    public PluginComponentDocumentation {
        purpose = purpose == null ? "" : purpose;
        usageExample = usageExample == null ? "" : usageExample;
        lifecyclePhases = List.copyOf(lifecyclePhases);
        supportedDesiredStates = List.copyOf(supportedDesiredStates);
        configuration = List.copyOf(configuration);
        if (configuration.stream().map(PluginConfigurationDocumentation::path).distinct().count() != configuration.size()) {
            throw new IllegalArgumentException("component configuration paths must be unique");
        }
        emittedEvents = List.copyOf(emittedEvents);
    }

    /** Compatibility constructor for callers that do not yet publish an option specification. */
    public PluginComponentDocumentation(String purpose, String usageExample, boolean lifecycle,
                                        List<String> lifecyclePhases, List<String> supportedDesiredStates,
                                        List<PluginEventDocumentation> emittedEvents) {
        this(purpose, usageExample, lifecycle, lifecyclePhases, supportedDesiredStates, List.of(), emittedEvents);
    }

    /** Compatibility constructor for code-registered components; archive scanning performs richer capability detection. */
    public PluginComponentDocumentation(String purpose, String usageExample, boolean lifecycle,
                                        List<String> lifecyclePhases, List<PluginEventDocumentation> emittedEvents) {
        this(purpose, usageExample, lifecycle, lifecyclePhases,
                lifecycle ? List.of("RUNNING", "STOPPED") : List.of("ACTIVE", "INACTIVE"), List.of(), emittedEvents);
    }
}
