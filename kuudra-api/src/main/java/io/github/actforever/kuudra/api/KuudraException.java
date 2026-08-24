package io.github.actforever.kuudra.api;

/** Stable unchecked boundary for failures produced by the Kuudra kernel. */
public class KuudraException extends RuntimeException {
    public KuudraException(String message) { super(message); }
    public KuudraException(String message, Throwable cause) { super(message, cause); }
    public static KuudraException wrap(String message, Throwable cause) {
        return cause instanceof KuudraException kuudra ? kuudra : new KuudraException(message, cause);
    }
}
