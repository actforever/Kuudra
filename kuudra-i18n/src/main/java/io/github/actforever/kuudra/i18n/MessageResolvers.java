package io.github.actforever.kuudra.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Catalog loading and resolver composition utilities independent from App and logging. */
public final class MessageResolvers {
    public static final String DEFAULT_LOCALE = "en_US";
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}_[A-Z]{2}");
    private static final MessageResolver ENGLISH = loadPackaged(DEFAULT_LOCALE, true);
    private MessageResolvers() { }

    public static MessageResolver english() { return ENGLISH; }
    /** Loads a home override for the preferred locale, then falls back to packaged en_US. */
    public static MessageResolver locale(Path localeDirectory, String preferredLocale) throws IOException {
        validateLocale(preferredLocale);
        Path directory = Objects.requireNonNull(localeDirectory, "localeDirectory").toAbsolutePath().normalize();
        Path catalog = directory.resolve(preferredLocale + ".json").normalize();
        if (!catalog.startsWith(directory)) throw new IOException("Locale catalog escapes locale directory: " + catalog);
        MessageResolver preferred;
        if (Files.isRegularFile(catalog)) {
            try (InputStream input = Files.newInputStream(catalog)) { preferred = json(input); }
        } else {
            preferred = loadPackaged(preferredLocale, false);
        }
        return layered(preferred, ENGLISH);
    }
    public static MessageResolver json(InputStream input) throws IOException { return JsonMessageResolver.read(input); }
    public static MessageResolver layered(MessageResolver primary, MessageResolver fallback) {
        Objects.requireNonNull(primary, "primary"); Objects.requireNonNull(fallback, "fallback");
        return (key, arguments) -> primary.resolve(key, arguments).or(() -> fallback.resolve(key, arguments));
    }

    public static void validateLocale(String locale) {
        if (locale == null || !LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("Locale must use xx_XX format: " + locale);
        }
    }

    private static MessageResolver loadPackaged(String locale, boolean required) {
        String resource = "/i18n/" + locale + ".json";
        try (InputStream input = MessageResolvers.class.getResourceAsStream(resource)) {
            if (input == null) {
                if (required) throw new IllegalStateException("Packaged default catalog is missing: " + resource);
                return MessageResolver.none();
            }
            return json(input);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load packaged catalog: " + resource, error);
        }
    }
}
