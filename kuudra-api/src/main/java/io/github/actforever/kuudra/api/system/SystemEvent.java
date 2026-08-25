package io.github.actforever.kuudra.api.system;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Observation-only runtime event; never re-enters the business signal pipeline. */
public record SystemEvent(UUID id, Instant occurredAt, SystemEventLevel level, String type, Map<String, Object> data) {
    public SystemEvent { data = Map.copyOf(data); }
    public static SystemEvent of(String type, Map<String, Object> data) {
        return new SystemEvent(UUID.randomUUID(), Instant.now(), SystemEventLevel.AUTO, type, data);
    }
    public static SystemEvent debug(String type, Map<String, Object> data) {
        return new SystemEvent(UUID.randomUUID(), Instant.now(), SystemEventLevel.DEBUG, type, data);
    }
    public static SystemEvent trace(String type, Map<String, Object> data) {
        return new SystemEvent(UUID.randomUUID(), Instant.now(), SystemEventLevel.TRACE, type, data);
    }
}
