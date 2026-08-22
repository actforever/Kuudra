package io.github.actforever.kuudra.api;

/** Transport-safe App status summary. */
public record AppSnapshot(AppStatus status, int queuedTasks, int flowCount, String detail) {
    public AppSnapshot { detail = detail == null ? "" : detail; }
}
