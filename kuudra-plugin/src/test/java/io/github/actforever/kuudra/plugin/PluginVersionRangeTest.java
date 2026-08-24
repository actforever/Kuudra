package io.github.actforever.kuudra.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVersionRangeTest {
    @Test
    void appliesInclusiveExclusiveAndOpenBounds() {
        PluginVersionRange range = PluginVersionRange.parse("[0.1.0,0.3.5)");
        assertTrue(range.contains("0.1.0"));
        assertTrue(range.contains("0.3.4"));
        assertFalse(range.contains("0.3.5"));
        assertTrue(PluginVersionRange.parse("[1.2.0,)").contains("2.0.0"));
        assertTrue(PluginVersionRange.parse("[1.2.0]").contains("1.2.0+build.7"));
        assertFalse(PluginVersionRange.parse("[1.2.0]").contains("1.2.0-alpha"));
    }

    @Test
    void rejectsIllegalPluginVersionsAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> PluginVersionRange.parse("[v1.0.0]"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionRange.parse("1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> PluginVersionRange.parse("[2.0.0,1.0.0]"));
        assertThrows(IllegalArgumentException.class, () -> new PluginMetadata(
                "bad", "demo", "latest", "demo.Plugin", java.util.List.of()));
    }
}
