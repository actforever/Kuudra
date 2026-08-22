package io.github.actforever.kuudra.plugin;

public enum PluginComponentKind {
    SIGNAL_SOURCE("signal-source"), ROOT_SIGNAL_SOURCE("root-signal-source"),
    RAW_SIGNAL_PROCESSOR("raw-signal-processor"), SIGNAL_PROCESSOR("signal-processor"),
    SESSION_PROCESSOR("session-processor"), SIGNAL_ADAPTER("signal-adapter"), ACTOR("actor"), ACTION("action");
    private final String prefix;
    PluginComponentKind(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
}
