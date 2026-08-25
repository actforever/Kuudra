package io.github.actforever.kuudra.api.context;

import io.github.actforever.kuudra.api.action.*;
import io.github.actforever.kuudra.api.app.*;
import io.github.actforever.kuudra.api.component.*;
import io.github.actforever.kuudra.api.context.*;
import io.github.actforever.kuudra.api.event.*;
import io.github.actforever.kuudra.api.lifecycle.*;
import io.github.actforever.kuudra.api.runtime.*;
import io.github.actforever.kuudra.api.session.*;
import io.github.actforever.kuudra.api.system.*;

/** Process-wide codec selection point. JSON is the built-in default. */
public final class ContextCodecs {
    private static volatile ContextCodec defaultCodec = new JsonContextCodec();

    private ContextCodecs() { }

    public static ContextCodec defaultCodec() { return defaultCodec; }

    public static void setDefault(ContextCodec codec) {
        defaultCodec = java.util.Objects.requireNonNull(codec, "codec");
    }
}
