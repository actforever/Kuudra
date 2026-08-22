package io.github.actforever.kuudra.api;

/** Optional cooperative cancellation propagation from a parent Session to derived child Sessions. */
public enum ParentTerminationPolicy { NONE, ON_PARENT_CANCELLATION, ON_PARENT_TERMINAL }
