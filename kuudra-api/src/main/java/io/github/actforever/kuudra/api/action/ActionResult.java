package io.github.actforever.kuudra.api.action;

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

/** Action output may emit Events for the next graph edges. */
public record ActionResult(List<KuudraEvent> emissions) {
    public ActionResult { emissions = List.copyOf(emissions); }
    public static ActionResult empty() { return new ActionResult(List.of()); }
}
