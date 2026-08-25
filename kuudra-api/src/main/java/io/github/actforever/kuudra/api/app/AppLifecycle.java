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

/** Management port implemented by the App facade and exposed by transport adapters. */
public interface AppLifecycle {
    AppSnapshot snapshot();
    void start();
    void stop();
    default void restart() { stop(); start(); }
}
