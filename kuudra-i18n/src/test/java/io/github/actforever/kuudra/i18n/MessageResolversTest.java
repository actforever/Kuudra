package io.github.actforever.kuudra.i18n;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageResolversTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void loadsJsonInterpolatesArgumentsAndUsesWildcardFallback() throws Exception {
        MessageResolver resolver = MessageResolvers.json(new ByteArrayInputStream("""
                {"hello":"Hello {name}","*":"Unknown {messageKey} {arguments}"}
                """.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Hello Kuudra", resolver.resolve("hello", Map.of("name", "Kuudra")).orElseThrow());
        assertTrue(resolver.resolve("missing", Map.of("value", 1)).orElseThrow().startsWith("Unknown missing"));
    }

    @Test void loadsPreferredHomeCatalogAndFallsBackToEnglish() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("zh_CN.json"), "{\"app.stopping\":\"正在停止 {status}\"}");
        MessageResolver resolver = MessageResolvers.locale(directory, "zh_CN");
        org.junit.jupiter.api.Assertions.assertEquals("正在停止 STOPPING",
                resolver.resolve("app.stopping", Map.of("status", "STOPPING")).orElseThrow());
        org.junit.jupiter.api.Assertions.assertTrue(resolver.resolve("runtime.shutdown.started",
                Map.of("activeSessions", 0, "queuedTasks", 0)).orElseThrow().startsWith("Runtime shutdown started"));
    }

    @Test void pluginCatalogsAreIdentityScopedAndUseLocaleFallback() throws Exception {
        PluginMessageCatalogs catalogs = new PluginMessageCatalogs("zh_CN");
        catalogs.register("kuudra-official", "default", "en_US", new ByteArrayInputStream("{\"ready\":\"Ready {name}\"}".getBytes()));
        assertEquals("Ready core", catalogs.resolve("plugin.kuudra-official.default.ready", Map.of("name", "core")).orElseThrow());
        org.junit.jupiter.api.Assertions.assertTrue(catalogs.resolve("plugin.other.default.ready", Map.of()).isEmpty());
    }

    @Test void composesExternalCatalogBeforeEnglishFallback() {
        MessageResolver resolver = MessageResolvers.layered(
                (key, arguments) -> key.equals("app.stopping") ? Optional.of("Override") : Optional.empty(),
                MessageResolvers.english());
        assertEquals("Override", resolver.resolve("app.stopping", Map.of("status", "STOPPING")).orElseThrow());
        assertTrue(resolver.resolve("runtime.shutdown.started", Map.of("activeSessions", 0, "queuedTasks", 0))
                .orElseThrow().startsWith("Runtime shutdown started"));
    }
}
