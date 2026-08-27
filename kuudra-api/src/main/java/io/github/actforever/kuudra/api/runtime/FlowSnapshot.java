package io.github.actforever.kuudra.api.runtime;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

public record FlowSnapshot(String flowId, FlowExecutionClass executionClass,
                           int activeSessions, int deferredTasks) {
    public FlowSnapshot(String flowId, int activeSessions, int deferredTasks) {
        this(flowId, FlowExecutionClass.DATA, activeSessions, deferredTasks);
    }
}
