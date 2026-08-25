package io.github.actforever.kuudra.api.session;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

public enum SessionSchedulingPolicy {
    PARALLEL, SERIAL, IGNORE, CANCEL_AND_REPLACE_PENDING, CANCEL_AND_KEEP_PENDING, TOGGLE
}
