package io.github.actforever.kuudra.api;

public record FlowSnapshot(String flowId, FlowStatus status, int activeSessions, int deferredTasks) { }
