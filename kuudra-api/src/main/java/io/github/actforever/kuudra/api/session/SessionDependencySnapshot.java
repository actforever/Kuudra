package io.github.actforever.kuudra.api.session;

import java.util.UUID;

/** Read-only observation of one active dependent -> required session edge. */
public record SessionDependencySnapshot(UUID dependentSessionId, UUID requiredSessionId,
                                        SessionTerminationPolicy terminationPolicy) { }
