package io.github.actforever.kuudra.api;

public enum SessionSchedulingPolicy {
    PARALLEL, SERIAL, IGNORE, CANCEL_AND_REPLACE_PENDING, CANCEL_AND_KEEP_PENDING, TOGGLE
}
