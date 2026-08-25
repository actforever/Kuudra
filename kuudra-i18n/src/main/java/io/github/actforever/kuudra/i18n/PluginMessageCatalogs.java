package io.github.actforever.kuudra.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Locale-aware plugin catalog registry using plugin.{namespace}.{pluginId}.{localKey}. */
public final class PluginMessageCatalogs implements MessageResolver {
    private final String preferredLocale;
    private final Map<String, MessageResolver> catalogs = new ConcurrentHashMap<>();

    public PluginMessageCatalogs(String preferredLocale) {
        MessageResolvers.validateLocale(preferredLocale);
        this.preferredLocale = preferredLocale;
    }

    public String preferredLocale() { return preferredLocale; }

    public void register(String namespace, String pluginId, String locale, InputStream input) throws IOException {
        MessageResolvers.validateLocale(locale);
        catalogs.put(namespace + "/" + pluginId + "/" + locale, MessageResolvers.json(input));
    }

    @Override public Optional<String> resolve(String key, Map<String, Object> arguments) {
        if (!key.startsWith("plugin.")) return Optional.empty();
        String[] parts = key.split("\\.", 4);
        if (parts.length != 4) return Optional.empty();
        String identity = parts[1] + "/" + parts[2] + "/";
        MessageResolver preferred = catalogs.getOrDefault(identity + preferredLocale, MessageResolver.none());
        MessageResolver fallback = catalogs.getOrDefault(identity + MessageResolvers.DEFAULT_LOCALE, MessageResolver.none());
        return preferred.resolve(parts[3], arguments).or(() -> fallback.resolve(parts[3], arguments));
    }
}
