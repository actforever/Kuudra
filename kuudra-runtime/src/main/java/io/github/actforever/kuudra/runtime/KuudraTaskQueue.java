package io.github.actforever.kuudra.runtime;

import java.time.Duration;
import java.util.Optional;

/** Replaceable bounded queue abstraction used by every runtime signal stage. */
public interface KuudraTaskQueue extends AutoCloseable {
    boolean offer(RuntimeTask task);
    Optional<RuntimeTask> poll(Duration timeout) throws InterruptedException;
    int size();
    @Override void close();
}
