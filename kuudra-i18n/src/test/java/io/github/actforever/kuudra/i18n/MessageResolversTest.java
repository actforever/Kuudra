package io.github.actforever.kuudra.i18n;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageResolversTest {
    @Test void loadsJsonInterpolatesArgumentsAndUsesWildcardFallback() throws Exception {
        MessageResolver resolver = MessageResolvers.json(new ByteArrayInputStream("""
                {"hello":"Hello {name}","*":"Unknown {messageKey} {arguments}"}
                """.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Hello Kuudra", resolver.resolve("hello", Map.of("name", "Kuudra")).orElseThrow());
        assertTrue(resolver.resolve("missing", Map.of("value", 1)).orElseThrow().startsWith("Unknown missing"));
    }

    @Test void composesExternalCatalogBeforeEnglishFallback() {
        MessageResolver resolver = MessageResolvers.layered(
                (key, arguments) -> key.equals("app.stopping") ? Optional.of("Override") : Optional.empty(),
                MessageResolvers.english());
        assertEquals("Override", resolver.resolve("app.stopping", Map.of("status", "STOPPING")).orElseThrow());
        assertTrue(resolver.resolve("runtime.shutdown.started", Map.of("activeSessions", 0, "queuedTasks", 0))
                .orElseThrow().startsWith("Runtime shutdown started"));
    }
}
