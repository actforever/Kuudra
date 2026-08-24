package io.github.actforever.kuudra.api;

public record FlowSnapshot(String flowId, int activeSessions, int deferredTasks) { }
