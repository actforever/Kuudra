package io.github.actforever.kuudra.plugin;

import io.github.actforever.kuudra.api.component.EventSource;
import io.github.actforever.kuudra.api.event.EventEmitter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginComponentRegistryTest {
    @Test
    void isolatesEqualComponentNamesByPluginIdentity() {
        PluginComponentRegistry registry = new PluginComponentRegistry();
        registry.register(new PluginComponentDefinition("alpha", "demo", PluginComponentKind.EVENT_SOURCE,
                "source", FirstSource.class));
        registry.register(new PluginComponentDefinition("beta", "demo", PluginComponentKind.EVENT_SOURCE,
                "source", SecondSource.class));

        assertEquals(FirstSource.class,
                registry.find("event-source/demo/alpha/source").orElseThrow().implementation());
        assertEquals(SecondSource.class,
                registry.find("event-source/demo/beta/source").orElseThrow().implementation());
    }

    public static final class FirstSource implements EventSource {
        @Override public void setEmitter(EventEmitter emitter) { }
    }

    public static final class SecondSource implements EventSource {
        @Override public void setEmitter(EventEmitter emitter) { }
    }
}
