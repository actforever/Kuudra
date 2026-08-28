package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.ActionContext;
import io.github.actforever.kuudra.api.event.EventDomain;
import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Precompiled lookup of a dynamic Event/Session/Flow/Global value. */
public final class ContextValueReference {
    private static final java.util.Set<String> SCOPES = java.util.Set.of("event", "session", "flow", "global");
    private final String source;
    private final String scope;
    private final String[] path;

    private ContextValueReference(String source, String scope, String[] path) {
        this.source = source;
        this.scope = scope;
        this.path = path;
    }

    public static ContextValueReference compile(String reference, EventDomain domain) {
        Objects.requireNonNull(domain, "domain");
        String source = Objects.requireNonNull(reference, "reference").trim();
        if (source.startsWith("${") && source.endsWith("}")) source = source.substring(2, source.length() - 1);
        int separator = source.indexOf('#');
        String scope = separator < 0 ? null : source.substring(0, separator);
        String expression = separator < 0 ? source : source.substring(separator + 1);
        if (scope != null && !SCOPES.contains(scope)) throw new IllegalArgumentException("Unknown context scope: " + scope);
        if (domain == EventDomain.RAW && "session".equals(scope))
            throw new IllegalArgumentException("RAW-domain reference cannot use Session scope: " + reference);
        String[] path = expression.split("\\.");
        if (expression.isBlank() || java.util.Arrays.stream(path).anyMatch(String::isBlank))
            throw new IllegalArgumentException("Invalid context reference: " + reference);
        return new ContextValueReference(reference, scope, path);
    }

    public Optional<Object> find(KuudraEvent event, ActionContext context) {
        Objects.requireNonNull(event, "event"); Objects.requireNonNull(context, "context");
        Map<String, Object> session = context.sessionContext() == null ? Map.of() : context.sessionContext().snapshot();
        Map<String, Object> flow = context.flowContext() == null ? context.flowValues() : context.flowContext().snapshot();
        Map<String, Object> global = context.globalContext() == null ? context.globalValues() : context.globalContext().snapshot();
        return lookup(event, context.flowId(), session, flow, global, context.sessionContext() != null);
    }

    public Object get(KuudraEvent event, ActionContext context) {
        return find(event, context).orElseThrow(() -> new IllegalArgumentException("Unresolved context reference: " + source));
    }

    public <T> T get(KuudraEvent event, ActionContext context, Class<T> type) {
        return get(event, context, (Type) type);
    }

    public <T> T get(KuudraEvent event, ActionContext context, Type type) {
        return ContextCodecs.defaultCodec().decode(get(event, context), type);
    }

    private Optional<Object> lookup(KuudraEvent event, String flowId, Map<String, Object> session,
                                    Map<String, Object> flow, Map<String, Object> global, boolean hasSession) {
        if (scope != null) return switch (scope) {
            case "event" -> event(event, path);
            case "session" -> hasSession ? nested(session, path) : Optional.empty();
            case "flow" -> path.length == 1 && path[0].equals("id") ? Optional.of(flowId) : nested(flow, path);
            case "global" -> nested(global, path);
            default -> Optional.empty();
        };
        for (Optional<Object> value : List.of(event(event, path), hasSession ? nested(session, path) : Optional.empty(),
                nested(flow, path), nested(global, path))) if (value.isPresent()) return value;
        return Optional.empty();
    }

    private static Optional<Object> event(KuudraEvent event, String[] path) {
        if (path.length == 1) {
            Optional<Object> metadata = switch (path[0]) {
                case "id" -> Optional.of(event.id().toString());
                case "type" -> Optional.of(event.type());
                case "occurredAt", "occurred-at" -> Optional.of(event.occurredAt().toString());
                default -> Optional.empty();
            };
            if (metadata.isPresent()) return metadata;
            Object found = null; boolean matched = false;
            for (Map<String, Object> namespace : event.data().namespaces().values()) {
                if (!namespace.containsKey(path[0])) continue;
                if (matched) throw new IllegalArgumentException("Ambiguous EventData key; include its namespace: " + path[0]);
                found = namespace.get(path[0]); matched = true;
            }
            return matched ? Optional.of(found) : Optional.empty();
        }
        if (path[0].equals("data")) return nested(event.data().namespaces(), java.util.Arrays.copyOfRange(path, 1, path.length));
        return nested(event.data().namespaces(), path);
    }

    private static Optional<Object> nested(Map<String, ?> root, String[] path) {
        Object current = root;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) return Optional.empty();
            current = map.get(part);
        }
        return Optional.ofNullable(current);
    }
}
