package io.github.actforever.kuudra.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceholderResolverTest {
    @Test
    void resolvesEventSessionGlobalAndFlowScopesWithoutStringifyingWholeValues() {
        Event event = Event.of("input.press", EventData.of("input", Map.of("key", "A")));
        EventContext context = new EventContext("demo", new SessionReference(UUID.randomUUID(), "demo"), Map.of("mode", "hold"), null, () -> false,
                Map.of("profile", "test"), Map.of());
        Map<String, Object> values = PlaceholderResolver.resolveMap(Map.of(
                "key", "${event.data.input.key}", "mode", "${session.values.mode}", "profile", "${global.profile}", "text", "${flow.id}:${event.type}"), event, context);
        assertEquals("A", values.get("key"));
        assertEquals("hold", values.get("mode"));
        assertEquals("test", values.get("profile"));
        assertEquals("demo:input.press", values.get("text"));
    }

    @Test
    void rejectsMissingValuesInsteadOfSilentlyProducingInvalidConfiguration() {
        Event event = Event.of("input", Map.of());
        EventContext context = new EventContext("flow", null, Map.of(), null, () -> false);
        assertThrows(IllegalArgumentException.class, () -> PlaceholderResolver.resolve("${global.missing}", event, context));
        assertThrows(IllegalStateException.class, () -> PlaceholderResolver.resolve("${session.id}", event, context));
    }
}
