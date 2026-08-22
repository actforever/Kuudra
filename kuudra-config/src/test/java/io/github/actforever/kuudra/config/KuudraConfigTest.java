package io.github.actforever.kuudra.config;

import io.github.actforever.kuudra.api.SessionPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KuudraConfigTest {
    @Test
    void compilesTheRestrictedYamlDemoConfiguration() throws Exception {
        KuudraConfig.DemoConfig config = KuudraConfig.loadDemo(new StringReader("""
                runtime:
                  queueCapacity: 32
                  actorThreads: 2
                flow:
                  id: demo
                  sessionName: demo-session
                  policy: IGNORE
                  acceptType: gesture.double
                action:
                  simulateKey: C
                ingress:
                  id: input
                  inputType: key.press
                  key: A
                  doublePressWindowMs: 500
                """));

        assertEquals(32, config.queueCapacity());
        assertEquals(SessionPolicy.IGNORE, config.policy());
        assertEquals("C", config.simulateKey());
    }

    @Test
    void rejectsYamlFeaturesOutsideTheMinimalCompilerContract() {
        assertThrows(IOException.class, () -> KuudraConfig.loadDemo(new StringReader("runtime:\n  queueCapacity: [32]\n")));
    }
}
