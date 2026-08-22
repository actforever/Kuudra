package io.github.actforever.kuudra.logging;

/** One kernel startup's event-to-log subscription and file lifecycle. */
public interface KuudraLogSession extends AutoCloseable {
    @Override void close();
}
