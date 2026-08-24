package io.github.actforever.kuudra.defaultplugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultPluginBundleTest {
    @Test void exposesOfficialDefaultIngressAndEgress() {
        var plugin = DefaultPluginBundle.loadedPlugin();
        assertEquals("kuudra-official", plugin.metadata().namespace());
        assertEquals("default", plugin.metadata().id());
        assertEquals(java.util.List.of("egress/kuudra-official/default", "ingress/kuudra-official/default"),
                plugin.components().stream().map(component -> component.reference()).sorted().toList());
    }
}
