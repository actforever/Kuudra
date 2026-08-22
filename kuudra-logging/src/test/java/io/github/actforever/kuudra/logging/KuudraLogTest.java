package io.github.actforever.kuudra.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class KuudraLogTest {
    @Test
    void ownsAnIndependentLogbackContext() {
        assertEquals("kuudra-core", ((ch.qos.logback.classic.LoggerContext) KuudraLog.context()).getName());
        assertNotSame(LoggerFactory.getILoggerFactory(), KuudraLog.context());
    }
}
