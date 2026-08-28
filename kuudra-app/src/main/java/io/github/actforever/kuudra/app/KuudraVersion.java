package io.github.actforever.kuudra.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Runtime view of the Maven-filtered kernel version resource. */
final class KuudraVersion {
    private static final String CURRENT = load();

    private KuudraVersion() { }

    static String current() { return CURRENT; }

    private static String load() {
        try (InputStream input = KuudraVersion.class.getClassLoader()
                .getResourceAsStream("META-INF/kuudra/version.properties")) {
            if (input == null) return "development";
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", "development");
        } catch (IOException ignored) {
            return "development";
        }
    }
}
