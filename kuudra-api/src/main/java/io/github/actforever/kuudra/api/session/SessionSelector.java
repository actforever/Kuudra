package io.github.actforever.kuudra.api.session;

/**
 * Selects active sessions for a dependency edge. Blank flow/component/group fields are wildcards.
 * The ingress component identity is its canonical {@code ingress/namespace/name} resource address.
 */
public record SessionSelector(String flowId, String ingressComponentId, String groupKey,
                              SessionMatchPolicy matchPolicy) {
    public SessionSelector {
        flowId = normalize(flowId);
        ingressComponentId = normalize(ingressComponentId);
        groupKey = normalize(groupKey);
        if (flowId == null && ingressComponentId == null && groupKey == null) {
            throw new IllegalArgumentException("A session selector must constrain flowId, ingressComponentId, or groupKey");
        }
        if (matchPolicy == null) matchPolicy = SessionMatchPolicy.UNIQUE;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
