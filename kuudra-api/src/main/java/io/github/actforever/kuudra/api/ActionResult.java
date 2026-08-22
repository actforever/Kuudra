package io.github.actforever.kuudra.api;

import java.util.List;

/** Action output may emit Events for the next graph edges. */
public record ActionResult(List<Event> emissions) {
    public ActionResult { emissions = List.copyOf(emissions); }
    public static ActionResult empty() { return new ActionResult(List.of()); }
}
