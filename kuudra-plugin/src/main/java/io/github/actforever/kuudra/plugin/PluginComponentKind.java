package io.github.actforever.kuudra.plugin;

public enum PluginComponentKind {
    EVENT_SOURCE("event-source"), RAW_EVENT_INTERPRETER("raw-event-interpreter"), EVENT_ADAPTER("event-adapter"),
    INGRESS("ingress"), EVENT_HANDLER("event-handler"), EGRESS("egress"), ACTION("action");
    private final String prefix;
    PluginComponentKind(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
}
