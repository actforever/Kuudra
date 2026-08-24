package io.github.actforever.kuudra.plugin;

public record PluginEventDocumentation(String stage, String eventType, String description, String dataExample) {
    public PluginEventDocumentation {
        stage = stage == null ? "" : stage;
        eventType = eventType == null ? "" : eventType;
        description = description == null ? "" : description;
        dataExample = dataExample == null ? "" : dataExample;
    }
}
