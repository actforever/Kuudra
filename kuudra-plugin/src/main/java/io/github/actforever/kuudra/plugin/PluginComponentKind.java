package io.github.actforever.kuudra.plugin;

public enum PluginComponentKind {
    EVENT_SOURCE("event-source", "EventSource"),
    EVENT_INTERPRETER("event-interpreter", "EventInterpreter"),
    EVENT_ADAPTER("event-adapter", "EventAdapter"),
    INGRESS("ingress", "Ingress"),
    EVENT_HANDLER("event-handler", "EventHandler"),
    EGRESS("egress", "Egress");

    private final String prefix;
    private final String manifestKind;

    PluginComponentKind(String prefix, String manifestKind) {
        this.prefix = prefix;
        this.manifestKind = manifestKind;
    }

    public String prefix() { return prefix; }
    public String manifestKind() { return manifestKind; }
}
