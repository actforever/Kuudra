package io.github.actforever.kuudra.runtime;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class InMemoryKuudraTaskQueue implements KuudraTaskQueue {
    private final LinkedBlockingQueue<RuntimeTask> delegate;
    private final AtomicBoolean closed = new AtomicBoolean();

    InMemoryKuudraTaskQueue(int capacity) { delegate = new LinkedBlockingQueue<>(capacity); }
    @Override public boolean offer(RuntimeTask task) { return !closed.get() && delegate.offer(task); }
    @Override public Optional<RuntimeTask> poll(Duration timeout) throws InterruptedException {
        return Optional.ofNullable(delegate.poll(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
    }
    @Override public int size() { return delegate.size(); }
    @Override public void close() { closed.set(true); }
}
