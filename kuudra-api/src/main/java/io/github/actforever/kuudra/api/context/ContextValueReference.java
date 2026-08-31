package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.ActionContext;
import io.github.actforever.kuudra.api.event.EventDomain;
import io.github.actforever.kuudra.api.event.EventHandlerContext;
import io.github.actforever.kuudra.api.event.KuudraEvent;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Precompiled lookup of a dynamic Event/Session/Ability/Global value. */
public final class ContextValueReference {
    private static final java.util.Set<String> SCOPES = java.util.Set.of("event", "session", "ability", "global");
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
        if (source.indexOf('#') >= 0) throw new IllegalArgumentException(
                "Legacy '#' context reference has been removed; use a dot path: " + source.replace('#', '.'));
        String[] all = source.split("\\.");
        if (all.length < 2 || java.util.Arrays.stream(all).anyMatch(String::isBlank))
            throw new IllegalArgumentException("Context reference must use an explicit scope and dot path: " + reference);
        String scope = all[0];
        if (!SCOPES.contains(scope)) throw new IllegalArgumentException(
                "Context reference must begin with event, session, ability, or global: " + reference);
        if (domain == EventDomain.RAW && scope.equals("session"))
            throw new IllegalArgumentException("RAW-domain reference cannot use Session scope: " + reference);
        String[] path = java.util.Arrays.copyOfRange(all, 1, all.length);
        return new ContextValueReference(reference, scope, path);
    }

    public Optional<Object> find(KuudraEvent event, ActionContext context) {
        Objects.requireNonNull(event, "event"); Objects.requireNonNull(context, "context");
        Map<String, Object> session = context.sessionContext() == null ? Map.of() : context.sessionContext().snapshot();
        Map<String, Object> flow = context.flowContext() == null ? context.flowValues() : context.flowContext().snapshot();
        Map<String, Object> global = context.globalContext() == null ? context.globalValues() : context.globalContext().snapshot();
        if (scope.equals("global") && context.globalContext() instanceof PlaceholderResolver.GlobalTemplatesProvider provider) {
            EventContext execution = new EventContext(context.flowId(), context.sessionId() == null ? null
                    : new io.github.actforever.kuudra.api.session.SessionReference(context.sessionId(), context.flowId()),
                    session, context.sessionContext(), flow, context.flowContext(), context.executionControl(),
                    global, context.globalContext(), Map.of());
            return Optional.ofNullable(provider.compiledGlobals().resolve(path, event, execution,
                    context.sessionContext() == null ? EventDomain.RAW : EventDomain.SESSION));
        }
        return lookup(event, context.flowId(), context.sessionId(), session, flow, global,
                context.sessionContext() != null);
    }

    public Optional<Object> find(KuudraEvent event, EventHandlerContext context) {
        Objects.requireNonNull(event, "event"); Objects.requireNonNull(context, "context");
        if (scope.equals("global") && context.global() instanceof PlaceholderResolver.GlobalTemplatesProvider provider) {
            EventContext execution = new EventContext(context.abilityId(),
                    new io.github.actforever.kuudra.api.session.SessionReference(context.sessionId(), context.abilityId()),
                    context.session().snapshot(), context.session(), context.ability().snapshot(), null,
                    context.executionControl(), context.global().snapshot(), context.global(), Map.of());
            return Optional.ofNullable(provider.compiledGlobals().resolve(path, event, execution, EventDomain.SESSION));
        }
        return lookup(event, context.abilityId(), context.sessionId(), context.session().snapshot(),
                context.ability().snapshot(), context.global().snapshot(), true);
    }

    public Object get(KuudraEvent event, EventHandlerContext context) {
        return find(event, context).orElseThrow(() -> new IllegalArgumentException(
                "Unresolved context reference: " + source));
    }

    public <T> T get(KuudraEvent event, EventHandlerContext context, Class<T> type) {
        return ContextCodecs.defaultCodec().decode(get(event, context), type);
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

    private Optional<Object> lookup(KuudraEvent event, String flowId, java.util.UUID sessionId,
                                    Map<String, Object> session,
                                    Map<String, Object> flow, Map<String, Object> global, boolean hasSession) {
        return switch (scope) {
            case "event" -> event(event, path);
            case "session" -> session(path, session, hasSession, sessionId, flowId);
            case "ability" -> path.length == 1 && path[0].equals("id") ? Optional.of(flowId)
                    : path.length > 1 && path[0].equals("values")
                    ? nested(flow, java.util.Arrays.copyOfRange(path, 1, path.length)) : Optional.empty();
            case "global" -> nested(global, path);
            default -> Optional.empty();
        };
    }

    private static Optional<Object> event(KuudraEvent event, String[] path) {
        if (path.length == 1) {
            Optional<Object> metadata = switch (path[0]) {
                case "id" -> Optional.of(event.id().toString());
                case "type" -> Optional.of(event.type());
                case "occurredAt" -> Optional.of(event.occurredAt().toString());
                default -> Optional.empty();
            };
            if (metadata.isPresent()) return metadata;
            return Optional.empty();
        }
        return path[0].equals("data")
                ? nested(event.data().namespaces(), java.util.Arrays.copyOfRange(path, 1, path.length))
                : Optional.empty();
    }

    private static Optional<Object> session(String[] path, Map<String,Object> values, boolean hasSession,
                                            java.util.UUID sessionId, String abilityId) {
        if (!hasSession) return Optional.empty();
        if (path.length == 1 && path[0].equals("id")) return Optional.of(sessionId.toString());
        if (path.length == 1 && path[0].equals("abilityId")) return Optional.of(abilityId);
        if (path.length > 1 && path[0].equals("values"))
            return nested(values, java.util.Arrays.copyOfRange(path, 1, path.length));
        return Optional.empty();
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
