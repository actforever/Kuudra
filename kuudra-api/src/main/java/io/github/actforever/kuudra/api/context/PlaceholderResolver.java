package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

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
    private static final java.util.Set<String> SCOPES = java.util.Set.of("event", "session", "ability", "global");
    private PlaceholderResolver() { }

    /** Implemented by Runtime Global Contexts that carry a precompiled Profile template graph. */
    public interface GlobalTemplatesProvider {
        CompiledGlobals compiledGlobals();
        default boolean templateActive(String key) { return true; }
    }

    public static Map<String, Object> resolveMap(Map<String, Object> template, KuudraEvent event, EventContext context) {
        return compileMap(template).resolve(event, context);
    }

    public static Object resolve(Object template, KuudraEvent event, EventContext context) {
        return compile(template, null, null).resolve(event, context);
    }

    /** Compiles placeholder syntax once so event-time resolution only performs value lookup and result assembly. */
    public static CompiledMap compileMap(Map<String, Object> template) {
        Objects.requireNonNull(template, "template");
        return new CompiledMap(compile(template, null, null));
    }

    /** Compiles and rejects explicit scopes unavailable in the selected execution domain. */
    public static CompiledMap compileMap(Map<String, Object> template, EventDomain domain) {
        Objects.requireNonNull(domain, "domain");
        validateScopes(template, domain);
        return new CompiledMap(compile(template, null, domain));
    }

    /** Compiles node arguments against one Profile's already-parsed Global template graph. */
    public static CompiledMap compileMap(Map<String, Object> template, EventDomain domain, CompiledGlobals globals) {
        Objects.requireNonNull(domain, "domain"); Objects.requireNonNull(globals, "globals");
        validateScopes(template, domain); globals.validateConsumers(template, domain);
        return new CompiledMap(compile(template, globals, domain));
    }

    /** Parses and validates a complete KuudraProfile Global Context outside the event hot path. */
    public static CompiledGlobals compileGlobals(Map<String, Object> values) {
        return new CompiledGlobals(values);
    }

    private static void validateScopes(Object value, EventDomain domain) {
        if (value instanceof String text) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                String expression = matcher.group(1);
                if (expression.indexOf('#') >= 0) throw legacy(expression);
                String[] parts = expression(expression);
                String scope = parts[0];
                if (!SCOPES.contains(scope)) throw new IllegalArgumentException(
                        "Placeholder must begin with event, session, ability, or global: ${" + expression + "}");
                if (domain == EventDomain.RAW && scope.equals("session"))
                    throw new IllegalArgumentException("RAW-domain configuration cannot reference session scope: ${" + expression + "}");
            }
        } else if (value instanceof Map<?, ?> map) map.values().forEach(item -> validateScopes(item, domain));
        else if (value instanceof List<?> list) list.forEach(item -> validateScopes(item, domain));
    }

    private static Template compile(Object template, CompiledGlobals globals, EventDomain domain) {
        if (template instanceof String value) return compileString(value, globals, domain);
        if (template instanceof Map<?, ?> map) {
            Map<String, Template> compiled = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Placeholder template map keys must be strings");
                compiled.put(key, compile(entry.getValue(), globals, domain));
            }
            Map<String, Template> immutable = Map.copyOf(compiled);
            return (event, context) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                immutable.forEach((key, value) -> result.put(key, value.resolve(event, context)));
                return Map.copyOf(result);
            };
        }
        if (template instanceof List<?> list) {
            List<Template> compiled = list.stream().map(value -> compile(value, globals, domain)).toList();
            return (event, context) -> compiled.stream().map(value -> value.resolve(event, context)).toList();
        }
        return (event, context) -> template;
    }

    private static Template compileString(String value, CompiledGlobals globals, EventDomain domain) {
        boolean structuredLiteral = structuredLiteral(value);
        Matcher matcher = PLACEHOLDER.matcher(value);
        if (!matcher.find()) {
            Object literal = structuredLiteral ? ContextCodecs.defaultCodec().parseLiteral(value.trim()) : value;
            return (event, context) -> literal;
        }
        if (matcher.start() == 0 && matcher.end() == value.length()) {
            String[] expression = checkedExpression(matcher.group(1), domain);
            return (event, context) -> lookup(expression, event, context, globals, domain);
        }
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        do {
            if (matcher.start() > start) segments.add(new Literal(value.substring(start, matcher.start())));
            segments.add(new Expression(checkedExpression(matcher.group(1), domain), globals, domain));
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

    private static String[] checkedExpression(String source, EventDomain domain) {
        if (source.indexOf('#') >= 0) throw legacy(source);
        String[] parts = expression(source);
        if (!SCOPES.contains(parts[0])) throw new IllegalArgumentException(
                "Placeholder must begin with event, session, ability, or global: ${" + source + "}");
        if (domain == EventDomain.RAW && parts[0].equals("session")) throw new IllegalArgumentException(
                "RAW-domain configuration cannot reference session scope: ${" + source + "}");
        return parts;
    }

    public static final class CompiledMap {
        private final Template template;
        private CompiledMap(Template template) { this.template = template; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> resolve(KuudraEvent event, EventContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            return (Map<String, Object>) template.resolve(event, context);
        }
    }

    @FunctionalInterface
    private interface Template { Object resolve(KuudraEvent event, EventContext context); }
    private sealed interface Segment permits Literal, Expression { Object resolve(KuudraEvent event, EventContext context); }
    private record Literal(String value) implements Segment {
        @Override public Object resolve(KuudraEvent event, EventContext context) { return value; }
    }
    private record Expression(String[] parts, CompiledGlobals globals, EventDomain domain) implements Segment {
        @Override public Object resolve(KuudraEvent event, EventContext context) { return lookup(parts, event, context, globals, domain); }
    }

    private static Object lookup(String[] parts, KuudraEvent event, EventContext context,
                                 CompiledGlobals globals, EventDomain domain) {
        String expression = String.join(".", parts);
        return switch (parts[0]) {
            case "event" -> eventValue(parts, event, expression);
            case "session" -> sessionValue(parts, context, expression);
            case "ability" -> abilityValue(parts, context, expression);
            case "global" -> globals == null
                    ? nested(context.globalValues(), parts, 1, expression)
                    : globals.resolve(java.util.Arrays.copyOfRange(parts, 1, parts.length), event, context,
                    domain == null ? context.session() == null ? EventDomain.RAW : EventDomain.SESSION : domain);
            default -> throw unresolved(expression);
        };
    }
    private static Object eventValue(String[] parts, KuudraEvent event, String expression) {
        if (parts.length == 2) return switch (parts[1]) {
            case "id" -> event.id().toString(); case "type" -> event.type(); case "occurredAt" -> event.occurredAt().toString(); default -> throw unresolved(expression);
        };
        if (parts.length >= 3 && parts[1].equals("data")) return nested(event.data().namespaces(), parts, 2, expression);
        throw unresolved(expression);
    }
    private static Object sessionValue(String[] parts, EventContext context, String expression) {
        if (context.session() == null) throw new IllegalStateException("Placeholder requires a Session: ${" + expression + "}");
        if (parts.length == 2) return switch (parts[1]) {
            case "id" -> context.session().id().toString(); case "abilityId" -> context.session().flowId(); default -> throw unresolved(expression);
        };
        if (parts.length >= 3 && parts[1].equals("values")) return nested(context.sessionValues(), parts, 2, expression);
        throw unresolved(expression);
    }
    private static Object abilityValue(String[] parts, EventContext context, String expression) {
        if (parts.length == 2 && parts[1].equals("id")) return context.flowId();
        if (parts.length >= 3 && parts[1].equals("values")) return nested(context.flowValues(), parts, 2, expression);
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
    /** Opaque, reusable Profile Global template graph. */
    public static final class CompiledGlobals {
        private final Map<String, Object> source;
        private final Map<String, java.util.Set<String>> dependencies = new LinkedHashMap<>();
        private final Map<String, Boolean> sessionDependent = new LinkedHashMap<>();
        private final Map<String, Template> sessionTemplates = new LinkedHashMap<>();
        private final Map<String, Template> rawTemplates = new LinkedHashMap<>();
        private final java.util.Set<String> templated = new java.util.LinkedHashSet<>();

        private CompiledGlobals(Map<String, Object> values) {
            Objects.requireNonNull(values, "values");
            Map<String, Object> checked = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("Global Context keys must not be blank");
                checked.put(key, value);
            });
            source = Map.copyOf(checked);
            source.forEach((key, value) -> {
                ReferenceScan scan = scan(value);
                dependencies.put(key, java.util.Set.copyOf(scan.globals));
                if (scan.placeholder) templated.add(key);
            });
            dependencies.forEach((key, refs) -> refs.forEach(ref -> {
                if (!source.containsKey(ref)) throw new IllegalArgumentException(
                        "Global Context " + key + " references missing global." + ref);
            }));
            for (String key : source.keySet()) sessionDependent.put(key,
                    sessionDependent(key, new java.util.LinkedHashSet<>()));
            for (String key : templated) {
                sessionTemplates.put(key, compile(source.get(key), this, EventDomain.SESSION));
                if (!sessionDependent.get(key)) rawTemplates.put(key, compile(source.get(key), this, EventDomain.RAW));
            }
        }

        public Map<String, Object> source() { return source; }

        private void validateConsumers(Object value, EventDomain domain) {
            ReferenceScan scan = scan(value);
            for (String key : scan.globals) {
                if (!source.containsKey(key)) throw new IllegalArgumentException("Placeholder references missing global." + key);
                if (domain == EventDomain.RAW && sessionDependent.get(key)) throw new IllegalArgumentException(
                        "RAW-domain configuration indirectly references Session through global." + key);
            }
        }

        public Object resolve(String[] path, KuudraEvent event, EventContext context, EventDomain domain) {
            if (path.length == 0) throw unresolved("global");
            String key = path[0];
            if (!source.containsKey(key)) throw unresolved("global." + String.join(".", path));
            Object live = context.globalValues().get(key);
            Object root;
            boolean activeTemplate = !(context.globalContext() instanceof GlobalTemplatesProvider provider)
                    || provider.templateActive(key);
            if (!templated.contains(key) || !activeTemplate) root = live;
            else {
                if (domain == EventDomain.RAW && sessionDependent.get(key)) throw new IllegalArgumentException(
                        "RAW-domain configuration indirectly references Session through global." + key);
                Template template = domain == EventDomain.RAW ? rawTemplates.get(key) : sessionTemplates.get(key);
                root = template.resolve(event, context);
            }
            if (path.length == 1) return root;
            return nestedValue(root, path, 1, "global." + String.join(".", path));
        }

        private boolean sessionDependent(String key, java.util.Set<String> visiting) {
            Boolean known = sessionDependent.get(key); if (known != null) return known;
            if (!visiting.add(key)) throw new IllegalArgumentException(
                    "Circular Global Context placeholder reference: " + String.join(" -> ", visiting) + " -> " + key);
            ReferenceScan own = scan(source.get(key));
            boolean result = own.session;
            for (String dependency : dependencies.get(key)) result |= sessionDependent(dependency, visiting);
            visiting.remove(key); return result;
        }

        private static ReferenceScan scan(Object value) {
            ReferenceScan result = new ReferenceScan(); scan(value, result); return result;
        }
        private static void scan(Object value, ReferenceScan result) {
            if (value instanceof String text) {
                Matcher matcher = PLACEHOLDER.matcher(text);
                while (matcher.find()) {
                    result.placeholder = true;
                    String[] parts = checkedExpression(matcher.group(1), EventDomain.SESSION);
                    if (parts[0].equals("session")) result.session = true;
                    if (parts[0].equals("global")) {
                        if (parts.length < 2) throw unresolved(matcher.group(1));
                        result.globals.add(parts[1]);
                    }
                }
            } else if (value instanceof Map<?, ?> map) map.values().forEach(item -> scan(item, result));
            else if (value instanceof List<?> list) list.forEach(item -> scan(item, result));
        }
        private static final class ReferenceScan {
            final java.util.Set<String> globals = new java.util.LinkedHashSet<>();
            boolean session; boolean placeholder;
        }
    }

    private static Object nestedValue(Object root, String[] parts, int start, String expression) {
        Object current = root;
        for (int index = start; index < parts.length; index++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[index])) throw unresolved(expression);
            current = map.get(parts[index]);
        }
        return current;
    }
    private static IllegalArgumentException legacy(String expression) {
        return new IllegalArgumentException("Legacy '#' placeholder syntax has been removed; use an explicit dot path: ${"
                + expression.replace('#', '.') + "}");
    }
    private static IllegalArgumentException unresolved(String expression) { return new IllegalArgumentException("Unresolved placeholder: ${" + expression + "}"); }
}
