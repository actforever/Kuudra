package io.github.actforever.kuudra.api;

/** Process-wide codec selection point. JSON is the built-in default. */
public final class ContextCodecs {
    private static volatile ContextCodec defaultCodec = new JsonContextCodec();

    private ContextCodecs() { }

    public static ContextCodec defaultCodec() { return defaultCodec; }

    public static void setDefault(ContextCodec codec) {
        defaultCodec = java.util.Objects.requireNonNull(codec, "codec");
    }
}
