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
        return compileMap(template).resolve(event, context);
    }

    public static Object resolve(Object template, Event event, EventContext context) {
        return compile(template).resolve(event, context);
    }

    /** Compiles placeholder syntax once so event-time resolution only performs value lookup and result assembly. */
    public static CompiledMap compileMap(Map<String, Object> template) {
        Objects.requireNonNull(template, "template");
        return new CompiledMap(compile(template));
    }

    private static Template compile(Object template) {
        if (template instanceof String value) return compileString(value);
        if (template instanceof Map<?, ?> map) {
            Map<String, Template> compiled = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Placeholder template map keys must be strings");
                compiled.put(key, compile(entry.getValue()));
            }
            Map<String, Template> immutable = Map.copyOf(compiled);
            return (event, context) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                immutable.forEach((key, value) -> result.put(key, value.resolve(event, context)));
                return Map.copyOf(result);
            };
        }
        if (template instanceof List<?> list) {
            List<Template> compiled = list.stream().map(PlaceholderResolver::compile).toList();
            return (event, context) -> compiled.stream().map(value -> value.resolve(event, context)).toList();
        }
        return (event, context) -> template;
    }

    private static Template compileString(String value) {
        boolean structuredLiteral = structuredLiteral(value);
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.find()) {
            Object literal = structuredLiteral ? ContextCodecs.defaultCodec().parseLiteral(value.trim()) : value;
            return (event, context) -> literal;
        }
        if (matcher.start() == 0 && matcher.end() == value.length()) {
            String[] expression = expression(matcher.group(1));
            return (event, context) -> lookup(expression, event, context);
        }
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        do {
            if (matcher.start() > start) segments.add(new Literal(value.substring(start, matcher.start())));
            segments.add(new Expression(expression(matcher.group(1))));
            start = matcher.end();
        } while (matcher.find());
        if (start < value.length()) segments.add(new Literal(value.substring(start)));
        List<Segment> immutable = List.copyOf(segments);
        return (event, context) -> {
            StringBuilder result = new StringBuilder();
            for (Segment segment : immutable) result.append(segment.resolve(event, context));
            String resolved = result.toString();
            return structuredLiteral ? ContextCodecs.defaultCodec().parseLiteral(resolved.trim()) : resolved;
        };
    }

    private static boolean structuredLiteral(String value) {
        String trimmed = value.trim();
        return trimmed.length() >= 2 && ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]")));
    }

    private static String[] expression(String expression) {
        String[] parts = expression.split("\\.");
        if (parts.length == 0 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) throw unresolved(expression);
        return parts;
    }

    public static final class CompiledMap {
        private final Template template;
        private CompiledMap(Template template) { this.template = template; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> resolve(Event event, EventContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            return (Map<String, Object>) template.resolve(event, context);
        }
    }

    @FunctionalInterface
    private interface Template { Object resolve(Event event, EventContext context); }
    private sealed interface Segment permits Literal, Expression { Object resolve(Event event, EventContext context); }
    private record Literal(String value) implements Segment {
        @Override public Object resolve(Event event, EventContext context) { return value; }
    }
    private record Expression(String[] parts) implements Segment {
        @Override public Object resolve(Event event, EventContext context) { return lookup(parts, event, context); }
    }

    private static Object lookup(String[] parts, Event event, EventContext context) {
        String expression = String.join(".", parts);
        int scopeSeparator = parts[0].indexOf('#');
        if (scopeSeparator >= 0) {
            String scope = parts[0].substring(0, scopeSeparator);
            String firstPath = parts[0].substring(scopeSeparator + 1);
            if (scope.isBlank() || firstPath.isBlank()) throw unresolved(expression);
            String[] path = parts.clone(); path[0] = firstPath;
            return scoped(scope, path, event, context, expression);
        }
        if (!java.util.Set.of("event", "session", "flow", "global").contains(parts[0])) {
            return automatic(parts, event, context, expression);
        }
        return switch (parts[0]) {
            case "event" -> eventValue(parts, event, expression);
            case "session" -> sessionValue(parts, context, expression);
            case "global" -> nested(context.globalValues(), parts, 1, expression);
            case "flow" -> parts.length == 2 && parts[1].equals("id") ? context.flowId() : unresolved(expression);
            default -> throw unresolved(expression);
        };
    }
    private static Object scoped(String scope, String[] path, Event event, EventContext context, String expression) {
        Lookup result = switch (scope) {
            case "event" -> eventLookup(path, event);
            case "session" -> context.session() == null ? Lookup.missing() : nestedFind(context.sessionValues(), path, 0);
            case "flow" -> path.length == 1 && path[0].equals("id") ? Lookup.found(context.flowId()) : nestedFind(context.flowValues(), path, 0);
            case "global" -> nestedFind(context.globalValues(), path, 0);
            default -> Lookup.missing();
        };
        if (!result.found) {
            if (scope.equals("session") && context.session() == null) throw new IllegalStateException("Placeholder requires a Session: ${" + expression + "}");
            throw unresolved(expression);
        }
        return result.value;
    }
    private static Object automatic(String[] path, Event event, EventContext context, String expression) {
        for (Lookup result : List.of(eventLookup(path, event),
                context.session() == null ? Lookup.missing() : nestedFind(context.sessionValues(), path, 0),
                nestedFind(context.flowValues(), path, 0), nestedFind(context.globalValues(), path, 0))) {
            if (result.found) return result.value;
        }
        throw unresolved(expression);
    }
    private static Lookup eventLookup(String[] path, Event event) {
        if (path.length == 1) {
            Lookup metadata = switch (path[0]) {
                case "id" -> Lookup.found(event.id().toString());
                case "type" -> Lookup.found(event.type());
                case "occurred-at", "occurredAt" -> Lookup.found(event.occurredAt().toString());
                default -> Lookup.missing();
            };
            if (metadata.found) return metadata;
            Object match = null; boolean found = false;
            for (Map.Entry<String, Map<String, Object>> namespace : event.data().namespaces().entrySet()) {
                if (!namespace.getValue().containsKey(path[0])) continue;
                if (found) throw new IllegalArgumentException("Ambiguous EventData key; include its namespace: " + path[0]);
                match = namespace.getValue().get(path[0]); found = true;
            }
            return found ? Lookup.found(match) : Lookup.missing();
        }
        if (path[0].equals("data")) return nestedFind(event.data().namespaces(), path, 1);
        return nestedFind(event.data().namespaces(), path, 0);
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
    private static Lookup nestedFind(Map<String, ?> root, String[] parts, int start) {
        Object current = root;
        for (int index = start; index < parts.length; index++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[index])) return Lookup.missing();
            current = map.get(parts[index]);
        }
        return Lookup.found(current);
    }
    private record Lookup(boolean found, Object value) {
        private static Lookup found(Object value) { return new Lookup(true, value); }
        private static Lookup missing() { return new Lookup(false, null); }
    }
    private static IllegalArgumentException unresolved(String expression) { return new IllegalArgumentException("Unresolved placeholder: ${" + expression + "}"); }
}
