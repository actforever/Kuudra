package io.github.actforever.kuudra.api;

/** Lifecycle state of the App facade, independent from any transport adapter. */
public enum AppStatus { CREATED, STARTING, RUNNING, PAUSING, PAUSED, RESUMING, STOPPING, STOPPED, FAILED }
