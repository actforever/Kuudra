package io.github.actforever.kuudra.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Catalog loading and resolver composition utilities independent from App and logging. */
public final class MessageResolvers {
    private static final MessageResolver ENGLISH = loadEnglish();
    private MessageResolvers() { }

    public static MessageResolver english() { return ENGLISH; }
    public static MessageResolver json(InputStream input) throws IOException { return JsonMessageResolver.read(input); }
    public static MessageResolver layered(MessageResolver primary, MessageResolver fallback) {
        Objects.requireNonNull(primary, "primary"); Objects.requireNonNull(fallback, "fallback");
        return (key, arguments) -> primary.resolve(key, arguments).or(() -> fallback.resolve(key, arguments));
    }

    private static MessageResolver loadEnglish() {
        try (InputStream input = MessageResolvers.class.getResourceAsStream("/i18n/en.json")) {
            if (input == null) throw new IllegalStateException("Packaged English catalog is missing: /i18n/en.json");
            return json(input);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load packaged English catalog", error);
        }
    }
}
