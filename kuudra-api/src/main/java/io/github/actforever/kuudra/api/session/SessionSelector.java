package io.github.actforever.kuudra.api.session;

/**
 * Selects active sessions by labels within the dependent Session's current Flow.
 * Every configured label must match; cross-Flow selection is intentionally unsupported.
 */
public record SessionSelector(java.util.Map<String, String> matchLabels, SessionMatchPolicy matchPolicy) {
    public SessionSelector {
        matchLabels = java.util.Map.copyOf(matchLabels);
        if (matchLabels.isEmpty()) throw new IllegalArgumentException("matchLabels must not be empty");
        if (matchPolicy == null) matchPolicy = SessionMatchPolicy.UNIQUE;
    }
}
