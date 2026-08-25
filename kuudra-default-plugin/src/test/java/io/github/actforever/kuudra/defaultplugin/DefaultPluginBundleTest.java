package io.github.actforever.kuudra.defaultplugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultPluginBundleTest {
    @Test void exposesOfficialBoundariesAndSystemControlHandler() {
        var plugin = DefaultPluginBundle.loadedPlugin();
        assertEquals("kuudra-official", plugin.metadata().namespace());
        assertEquals("default", plugin.metadata().id());
        assertEquals(java.util.List.of("egress/kuudra-official/default", "event-handler/kuudra-official/system-control", "ingress/kuudra-official/default"),
                plugin.components().stream().map(component -> component.reference()).sorted().toList());
        var ingress = plugin.components().stream().filter(component -> component.name().equals("default")
                && component.kind() == io.github.actforever.kuudra.plugin.PluginComponentKind.INGRESS).findFirst().orElseThrow();
        assertEquals(java.util.List.of("groupKey", "policy", "groupScope", "maxParallelSessions", "queueCapacity"),
                ingress.documentation().configuration().stream().map(property -> property.path()).toList());
        var control = plugin.components().stream().filter(component -> component.name().equals("system-control")).findFirst().orElseThrow();
        assertEquals("action", control.documentation().configuration().get(0).path());
        assertEquals(true, control.documentation().configuration().get(0).required());
    }
}
