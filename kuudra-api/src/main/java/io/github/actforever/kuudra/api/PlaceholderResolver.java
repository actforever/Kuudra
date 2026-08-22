package io.github.actforever.kuudra.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves YAML configuration placeholders only when a concrete Event execution scope exists. */
public final class PlaceholderResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private PlaceholderResolver() { }

    public static Map<String, Object> resolveMap(Map<String, Object> template, Event event, EventContext context) {
        @SuppressWarnings("unchecked") Map<String, Object> resolved = (Map<String, Object>) resolve(template, event, context);
        return resolved;
    }

    public static Object resolve(Object template, Event event, EventContext context) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(context, "context");
        if (template instanceof String value) return resolveString(value, event, context);
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Placeholder template map keys must be strings");
                result.put(key, resolve(entry.getValue(), event, context));
            }
            return Map.copyOf(result);
        }
        if (template instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object value : list) result.add(resolve(value, event, context));
            return List.copyOf(result);
        }
        return template;
    }

    private static Object resolveString(String template, Event event, EventContext context) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        if (!matcher.find()) return template;
        if (matcher.start() == 0 && matcher.end() == template.length()) return lookup(matcher.group(1), event, context);
        StringBuffer result = new StringBuffer();
        do { matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(lookup(matcher.group(1), event, context)))); }
        while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    private static Object lookup(String expression, Event event, EventContext context) {
        String[] parts = expression.split("\\.");
        if (parts.length < 2) throw unresolved(expression);
        return switch (parts[0]) {
            case "event" -> eventValue(parts, event, expression);
            case "session" -> sessionValue(parts, context, expression);
            case "global" -> nested(context.globalValues(), parts, 1, expression);
            case "flow" -> parts.length == 2 && parts[1].equals("id") ? context.flowId() : unresolved(expression);
            default -> throw unresolved(expression);
        };
    }
    private static Object eventValue(String[] parts, Event event, String expression) {
        if (parts.length == 2) return switch (parts[1]) {
            case "id" -> event.id().toString(); case "type" -> event.type(); case "occurredAt" -> event.occurredAt().toString(); default -> throw unresolved(expression);
        };
        if (parts.length >= 4 && parts[1].equals("data")) return nested(event.data().namespace(parts[2]), parts, 3, expression);
        throw unresolved(expression);
    }
    private static Object sessionValue(String[] parts, EventContext context, String expression) {
        if (context.session() == null) throw new IllegalStateException("Placeholder requires a Session: ${" + expression + "}");
        if (parts.length == 2) return switch (parts[1]) {
            case "id" -> context.session().id().toString(); case "flowId" -> context.session().flowId(); default -> throw unresolved(expression);
        };
        if (parts.length >= 3 && parts[1].equals("values")) return nested(context.sessionValues(), parts, 2, expression);
        throw unresolved(expression);
    }
    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, ?> root, String[] parts, int start, String expression) {
        Object current = root;
        for (int index = start; index < parts.length; index++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[index])) throw unresolved(expression);
            current = map.get(parts[index]);
        }
        return current;
    }
    private static IllegalArgumentException unresolved(String expression) { return new IllegalArgumentException("Unresolved placeholder: ${" + expression + "}"); }
}
