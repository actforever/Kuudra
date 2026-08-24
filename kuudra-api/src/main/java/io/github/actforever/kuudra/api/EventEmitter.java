package io.github.actforever.kuudra.api;

@FunctionalInterface
public interface EventEmitter { boolean emit(KuudraEvent event); }
