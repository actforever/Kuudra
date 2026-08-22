package io.github.actforever.kuudra.api;

import java.util.Objects;

/** Admission rule chosen by a SessionProcessor for one RawSignal. */
public record SessionSpec(String name, String admissionKey, SessionPolicy policy) {
    public SessionSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        admissionKey = admissionKey == null || admissionKey.isBlank() ? "default" : admissionKey;
        Objects.requireNonNull(policy, "policy");
    }
}
