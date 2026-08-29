package io.github.actforever.kuudra.plugin;

import java.util.List;

public record ResourceTemplateDocumentation(String purpose, List<String> lifecyclePhases,
                                            List<PluginConfigurationDocumentation> options,
                                            List<PluginConfigurationDocumentation> arguments,
                                            List<PluginEventDocumentation> emittedEvents) {
    public static final ResourceTemplateDocumentation EMPTY = new ResourceTemplateDocumentation(
            "", List.of(), List.of(), List.of(), List.of());

    public ResourceTemplateDocumentation {
        purpose = purpose == null ? "" : purpose;
        lifecyclePhases = List.copyOf(lifecyclePhases);
        options = unique(options, "option");
        arguments = unique(arguments, "argument");
        emittedEvents = List.copyOf(emittedEvents);
    }

    private static List<PluginConfigurationDocumentation> unique(
            List<PluginConfigurationDocumentation> values, String label) {
        List<PluginConfigurationDocumentation> copy = List.copyOf(values);
        if (copy.stream().map(PluginConfigurationDocumentation::path).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("resource " + label + " paths must be unique");
        }
        return copy;
    }
}
