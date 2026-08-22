package io.github.actforever.kuudra.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KuudraAppTest {
    @Test
    void exposesRuntimeStateWithoutLeakingRuntimeTypes() {
        try (KuudraApp app = new KuudraApp(8, 1)) {
            assertEquals("UP", app.health().status());
            assertEquals(0, app.flows().size());
        }
    }
}
