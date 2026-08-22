package io.github.actforever.kuudra.plugin;

public enum PluginComponentKind {
    EVENT_SOURCE("event-source"), EVENT_ADAPTER("event-adapter"), EVENT_PROCESSOR("event-processor"), ACTOR("actor"), ACTION("action");
    private final String prefix;
    PluginComponentKind(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
}
