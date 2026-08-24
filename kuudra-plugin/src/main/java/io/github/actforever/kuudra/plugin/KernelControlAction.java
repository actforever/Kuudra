package io.github.actforever.kuudra.plugin;

/** Commands accepted by the narrow plugin-to-kernel control port. */
public enum KernelControlAction {
    PAUSE_KERNEL, RESUME_KERNEL, STOP_KERNEL, PAUSE_SESSION, RESUME_SESSION, CANCEL_SESSION
}
