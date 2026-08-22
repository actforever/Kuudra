package io.github.actforever.kuudra.api;

/** Management port implemented by the App facade and exposed by transport adapters. */
public interface AppLifecycle {
    AppSnapshot snapshot();
    void start();
    void stop();
    default void restart() { stop(); start(); }
}
