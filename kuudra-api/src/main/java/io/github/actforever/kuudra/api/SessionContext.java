package io.github.actforever.kuudra.api;

/** Mutable only through atomic whole-map updates; every reader receives an immutable snapshot. */
public interface SessionContext extends ValueContext { }
