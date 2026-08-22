package io.github.actforever.kuudra.api;

import java.util.Objects;

/** Admission rule applied by a core SessionAllocator. */
public record SessionSpec(String name, String admissionKey, SessionPolicy policy, ParentTerminationPolicy parentTerminationPolicy) {
    public SessionSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        admissionKey = admissionKey == null || admissionKey.isBlank() ? "default" : admissionKey;
        Objects.requireNonNull(policy, "policy");
        parentTerminationPolicy = parentTerminationPolicy == null ? ParentTerminationPolicy.NONE : parentTerminationPolicy;
    }
    public SessionSpec(String name, String admissionKey, SessionPolicy policy) { this(name, admissionKey, policy, ParentTerminationPolicy.NONE); }
}
