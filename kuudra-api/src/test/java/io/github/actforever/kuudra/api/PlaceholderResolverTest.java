package io.github.actforever.kuudra.api;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceholderResolverTest {
    record KeyStroke(String key, boolean pressed) { }

    @Test
    void resolvesEventSessionGlobalAndFlowScopesWithoutStringifyingWholeValues() {
        KuudraEvent event = KuudraEvent.of("input.press", EventData.of("input", Map.of("key", "A")));
        EventContext context = new EventContext("demo", new SessionReference(UUID.randomUUID(), "demo"), Map.of("mode", "hold"), null, () -> ExecutionDecision.CONTINUE,
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
        KuudraEvent event = KuudraEvent.of("input", Map.of());
        EventContext context = new EventContext("flow", null, Map.of(), null, () -> ExecutionDecision.CONTINUE);
        assertThrows(IllegalArgumentException.class, () -> PlaceholderResolver.resolve("${global.missing}", event, context));
        assertThrows(IllegalStateException.class, () -> PlaceholderResolver.resolve("${session.id}", event, context));
    }

    @Test
    void compiledTemplateCanBeReusedAcrossEventsAndPreservesNestedValueTypes() {
        PlaceholderResolver.CompiledMap compiled = PlaceholderResolver.compileMap(Map.of(
                "value", "${event.data.input.value}",
                "nested", List.of(Map.of("label", "${flow.id}:${event.type}"))));
        EventContext context = new EventContext("demo", null, Map.of(), null, () -> ExecutionDecision.CONTINUE);

        Map<String, Object> first = compiled.resolve(KuudraEvent.of("first", EventData.of("input", Map.of("value", 7))), context);
        Map<String, Object> second = compiled.resolve(KuudraEvent.of("second", EventData.of("input", Map.of("value", true))), context);

        assertEquals(7, first.get("value"));
        assertEquals(true, second.get("value"));
        assertEquals("demo:first", ((Map<?, ?>) ((List<?>) first.get("nested")).get(0)).get("label"));
        assertEquals("demo:second", ((Map<?, ?>) ((List<?>) second.get("nested")).get(0)).get("label"));
    }

    @Test
    void eventDataEncodesPojoAsJsonTreeAndRestoresRequestedType() {
        EventData data = EventData.of("keyboard", Map.of()).with("keyboard", "stroke", new KeyStroke("A", true));

        assertEquals(Map.of("key", "A", "pressed", true), data.require("keyboard", "stroke"));
        assertEquals(new KeyStroke("A", true), data.get("keyboard", "stroke", KeyStroke.class));
    }

    @Test
    void preservesNativeLiteralsAndParsesJsonObjectAndArrayStrings() {
        KuudraEvent event = KuudraEvent.of("input", EventData.of("input", Map.of("key", "A")));
        EventContext context = new EventContext("flow", null, Map.of(), null, () -> ExecutionDecision.CONTINUE);

        Map<String, Object> values = PlaceholderResolver.resolveMap(Map.of(
                "number", 42,
                "flag", true,
                "numeric-text", "42",
                "boolean-text", "true",
                "object", "{\"key\":\"A\",\"count\":2}",
                "array", "[1,true,{\"key\":\"B\"}]",
                "dynamic", "{\"key\":\"${event#input.key}\"}"), event, context);

        assertEquals(42, values.get("number"));
        assertEquals(true, values.get("flag"));
        assertEquals("42", values.get("numeric-text"));
        assertEquals("true", values.get("boolean-text"));
        assertEquals(Map.of("key", "A", "count", 2), values.get("object"));
        assertEquals(List.of(1, true, Map.of("key", "B")), values.get("array"));
        assertEquals(Map.of("key", "A"), values.get("dynamic"));
    }

    @Test
    void rejectsMalformedStructuredJsonLiteral() {
        EventContext context = new EventContext("flow", null, Map.of(), null, () -> ExecutionDecision.CONTINUE);
        assertThrows(IllegalArgumentException.class,
                () -> PlaceholderResolver.resolve("{not-json}", KuudraEvent.of("input", Map.of()), context));
    }

    @Test
    void rawCompilationRejectsSessionScopeBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> PlaceholderResolver.compileMap(
                Map.of("illegal", "${session#mode}"), EventDomain.RAW));
        PlaceholderResolver.compileMap(Map.of("legal", "${event#type}"), EventDomain.RAW);
    }

    @Test
    void compiledContextReferenceReadsLatestMutableScopeAndRestoresPojo() {
        AtomicReference<Map<String, Object>> values = new AtomicReference<>(Map.of("key", Map.of("key", "A", "pressed", true)));
        SessionContext session = new SessionContext() {
            @Override public Map<String, Object> snapshot() { return values.get(); }
            @Override public boolean compareAndSet(Map<String, Object> expected, Map<String, Object> replacement) {
                return values.compareAndSet(expected, Map.copyOf(replacement));
            }
            @Override public Map<String, Object> update(UnaryOperator<Map<String, Object>> operation) {
                return values.updateAndGet(current -> Map.copyOf(operation.apply(current)));
            }
        };
        UUID sessionId = UUID.randomUUID();
        ActionContext context = new ActionContext(sessionId, "flow", session.snapshot(), session,
                () -> ExecutionDecision.CONTINUE, event -> true);
        ContextValueReference reference = ContextValueReference.compile("session#key", EventDomain.SESSION);
        KuudraEvent event = KuudraEvent.of("input", Map.of());

        assertEquals(new KeyStroke("A", true), reference.get(event, context, KeyStroke.class));
        session.put("key", new KeyStroke("B", false));
        assertEquals(new KeyStroke("B", false), reference.get(event, context, KeyStroke.class));
        assertThrows(IllegalArgumentException.class,
                () -> ContextValueReference.compile("session#key", EventDomain.RAW));
    }
}
