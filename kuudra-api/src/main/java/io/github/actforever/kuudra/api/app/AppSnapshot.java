package io.github.actforever.kuudra.api.app;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

/** Transport-safe App status summary. */
public record AppSnapshot(AppStatus status, int queuedTasks, int flowCount, String detail) {
    public AppSnapshot { detail = detail == null ? "" : detail; }
}
