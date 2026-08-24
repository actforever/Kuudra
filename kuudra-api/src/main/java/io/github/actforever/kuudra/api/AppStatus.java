package io.github.actforever.kuudra.api;

/** Lifecycle state of the App facade, independent from any transport adapter. */
public enum AppStatus { NEW, STARTING, RUNNING, PAUSED, STOPPING, STOPPED, FAILED }
