package io.github.actforever.kuudra.plugin;

public enum PluginComponentKind {
    EVENT_SOURCE("event-source"), EVENT_INTERPRETER("event-interpreter"), EVENT_ADAPTER("event-adapter"),
    INGRESS("ingress"), EVENT_HANDLER("event-handler"), EGRESS("egress");
    private final String prefix;
    PluginComponentKind(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }
}
