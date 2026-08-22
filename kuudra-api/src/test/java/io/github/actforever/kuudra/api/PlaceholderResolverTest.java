package io.github.actforever.kuudra.api;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
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

    @Test
    void compiledTemplateCanBeReusedAcrossEventsAndPreservesNestedValueTypes() {
        PlaceholderResolver.CompiledMap compiled = PlaceholderResolver.compileMap(Map.of(
                "value", "${event.data.input.value}",
                "nested", List.of(Map.of("label", "${flow.id}:${event.type}"))));
        EventContext context = new EventContext("demo", null, Map.of(), null, () -> false);

        Map<String, Object> first = compiled.resolve(Event.of("first", EventData.of("input", Map.of("value", 7))), context);
        Map<String, Object> second = compiled.resolve(Event.of("second", EventData.of("input", Map.of("value", true))), context);

        assertEquals(7, first.get("value"));
        assertEquals(true, second.get("value"));
        assertEquals("demo:first", ((Map<?, ?>) ((List<?>) first.get("nested")).get(0)).get("label"));
        assertEquals("demo:second", ((Map<?, ?>) ((List<?>) second.get("nested")).get(0)).get("label"));
    }
}
