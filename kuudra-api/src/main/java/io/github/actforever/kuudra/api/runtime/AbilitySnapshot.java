package io.github.actforever.kuudra.api.runtime;

/** Runtime execution snapshot for one registered Ability revision. */
public record AbilitySnapshot(String abilityId, long revision, AbilityExecutionClass executionClass,
                              int activeSessions, int deferredTasks) { }
