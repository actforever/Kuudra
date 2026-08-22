package io.github.actforever.kuudra.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignalModelTest {
    @Test
    void rawSignalCopiesPayloadAtBoundary() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", "A");
        RawSignal signal = new RawSignal(UUID.randomUUID(), "input.key.pressed", Instant.now(), payload);
        payload.put("key", "B");
        assertEquals("A", signal.payload().require(SignalData.CORE_NAMESPACE, "key"));
        assertThrows(UnsupportedOperationException.class, () -> signal.payload().namespace(SignalData.CORE_NAMESPACE).put("key", "C"));
    }

    @Test
    void rootSignalRequiresFlowAndSessionSpecification() {
        RawSignal raw = RawSignal.of("input", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new RootSignal(UUID.randomUUID(), raw, "", new SessionSpec("s", null, SessionPolicy.PARALLEL), Instant.now()));
    }

    @Test
    void signalDataKeepsPluginNamespacesIsolatedAndImmutable() {
        SignalData data = SignalData.of("input-plugin", Map.of("key", "A"))
                .with("gesture-plugin", "key", "DOUBLE_A");
        assertEquals("A", data.require("input-plugin", "key"));
        assertEquals("DOUBLE_A", data.require("gesture-plugin", "key"));
        assertThrows(UnsupportedOperationException.class, () -> data.namespace("input-plugin").put("key", "B"));
    }
}
