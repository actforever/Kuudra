package io.github.actforever.kuudra.plugin;

public enum ResourceTemplateKind {
    EVENT_SOURCE("event-source", "EventSource"),
    EVENT_INTERPRETER("event-interpreter", "EventInterpreter"),
    EVENT_ADAPTER("event-adapter", "EventAdapter"),
    INGRESS("ingress", "Ingress"),
    CONTROLLER("controller", "Controller"),
    EGRESS("egress", "Egress");

    private final String prefix;
    private final String manifestKind;
    ResourceTemplateKind(String prefix, String manifestKind) {
        this.prefix = prefix; this.manifestKind = manifestKind;
    }
    public String prefix() { return prefix; }
    public String manifestKind() { return manifestKind; }
}
