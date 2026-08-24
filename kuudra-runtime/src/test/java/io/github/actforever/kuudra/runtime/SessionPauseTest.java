package io.github.actforever.kuudra.runtime;

import io.github.actforever.kuudra.api.ContextCodecs;
import io.github.actforever.kuudra.api.SessionStatus;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SessionPauseTest {
    @Test void pausePreservesIdleSessionUntilResume() {
        AtomicInteger terminal = new AtomicInteger();
        SessionManager manager = new SessionManager(Runnable::run, ContextCodecs.defaultCodec(), ignored -> terminal.incrementAndGet());
        var session = manager.create("demo/flow", 1, "gate", "group", Map.of("value", 42));
        assertTrue(manager.pause(session.id));
        manager.completeIfIdle(session);
        assertEquals(SessionStatus.PAUSED, manager.snapshot(session.id).orElseThrow().status());
        assertEquals(0, terminal.get());
        assertEquals(42, session.context.snapshot().get("value"));
        assertTrue(manager.resume(session.id));
        assertEquals(SessionStatus.COMPLETED, manager.snapshot(session.id).orElseThrow().status());
        assertEquals(1, terminal.get());
    }
}
