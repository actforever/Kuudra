package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.api.system.SystemEvent;
import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP adapter for the App-owned SystemEvent stream. */
@RestController
@RequestMapping("/api/v1/system-events")
@Tag(name = "System Events")
public class SystemEventController {
    private final KuudraApp app;

    public SystemEventController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "订阅系统事件")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        EventStreamSubscription stream = new EventStreamSubscription(emitter);
        stream.attach(app.systemEvents().subscribe(stream::send));
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.close());
        return emitter;
    }

    public static final class EventStreamSubscription {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<AutoCloseable> subscription = new AtomicReference<>();

        public EventStreamSubscription(SseEmitter emitter) {
            this.emitter = emitter;
        }

        public void attach(AutoCloseable value) {
            if (!subscription.compareAndSet(null, value)) {
                closeQuietly(value);
                return;
            }
            if (closed.get()) closeQuietly(subscription.getAndSet(null));
        }

        public void send(SystemEvent event) {
            if (closed.get()) return;
            try {
                emitter.send(SseEmitter.event().id(event.id().toString()).name(event.type()).data(event));
            } catch (IOException | IllegalStateException disconnected) {
                close();
            }
        }

        public void close() {
            closed.set(true);
            closeQuietly(subscription.getAndSet(null));
        }

        public boolean closed() {
            return closed.get();
        }

        private static void closeQuietly(AutoCloseable value) {
            if (value != null) try {
                value.close();
            } catch (Exception ignored) {
                // Disconnect cleanup is intentionally silent.
            }
        }
    }
}
