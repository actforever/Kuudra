package io.github.actforever.kuudra.api.component;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

import java.util.List;

/** Filters or maps Event type/data while Runtime retains session ownership. */
@FunctionalInterface
public interface EventAdapter { List<KuudraEvent> adapt(KuudraEvent event, EventContext context); }
