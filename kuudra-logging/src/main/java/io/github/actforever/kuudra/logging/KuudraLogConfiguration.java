package io.github.actforever.kuudra.logging;

import java.util.Objects;

/** Public, host-framework-independent configuration for one kernel log session. */
public record KuudraLogConfiguration(
        KuudraLogLevel level,
        boolean consoleEnabled,
        boolean fileEnabled
) {
    public static final KuudraLogConfiguration DEFAULT = new KuudraLogConfiguration(
            KuudraLogLevel.INFO, true, true);

    public KuudraLogConfiguration {
        Objects.requireNonNull(level, "level");
    }
}
