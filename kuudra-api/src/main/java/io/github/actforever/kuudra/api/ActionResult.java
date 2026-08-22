package io.github.actforever.kuudra.api;

import java.util.List;

/** Action output may emit session-bound signals for the next graph edges. */
public record ActionResult(List<Signal> emissions) {
    public ActionResult { emissions = List.copyOf(emissions); }
    public static ActionResult empty() { return new ActionResult(List.of()); }
}
