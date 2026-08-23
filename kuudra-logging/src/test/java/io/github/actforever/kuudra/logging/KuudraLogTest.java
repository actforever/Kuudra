package io.github.actforever.kuudra.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.api.SystemEventBus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuudraLogTest {
    @TempDir Path directory;

    @Test
    void ownsAnIndependentLogbackContext() {
        assertEquals("kuudra-core", ((ch.qos.logback.classic.LoggerContext) KuudraLog.context()).getName());
        assertNotSame(LoggerFactory.getILoggerFactory(), KuudraLog.context());
    }

    @Test
    void writesLatestAndArchivesEachKernelRunWithIncrementingSequence() throws Exception {
        TestBus bus = new TestBus();
        Path logs = directory.resolve("logs");
        try (KuudraLogSession ignored = KuudraLog.openSession(logs, bus)) {
            bus.publish(SystemEvent.of("plugin.active", Map.of("pluginId", "keyboard")));
            assertTrue(Files.readString(logs.resolve("latest.log")).contains("plugin.active"));
        }
        assertTrue(Files.readString(logs.resolve("latest.log")).contains("plugin.active"));
        Path first;
        try (var files = Files.list(logs)) {
            first = files.filter(path -> path.getFileName().toString().endsWith("-1.log.gz")).findFirst().orElseThrow();
        }
        assertTrue(unzip(first).contains("pluginId=keyboard"));

        try (KuudraLogSession ignored = KuudraLog.openSession(logs, bus)) {
            assertEquals("", Files.readString(logs.resolve("latest.log")));
            bus.publish(SystemEvent.of("app.stopped", Map.of()));
        }
        assertTrue(Files.readString(logs.resolve("latest.log")).contains("app.stopped"));
        try (var files = Files.list(logs)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith("-2.log.gz")));
        }
    }

    @Test
    void appliesLevelAndFileOutputConfiguration() throws Exception {
        TestBus bus = new TestBus();
        Path logs = directory.resolve("configured-logs");
        KuudraLogConfiguration configuration = new KuudraLogConfiguration(KuudraLogLevel.ERROR, false, true);
        try (KuudraLogSession ignored = KuudraLog.openSession(logs, bus, configuration)) {
            bus.publish(SystemEvent.of("app.running", Map.of()));
            bus.publish(SystemEvent.of("app.failed", Map.of("reason", "test")));
        }
        String latest = Files.readString(logs.resolve("latest.log"));
        assertFalse(latest.contains("app.running"));
        assertTrue(latest.contains("app.failed"));
    }

    @Test
    void disabledFileOutputDoesNotReplaceOrArchiveLatestLog() throws Exception {
        TestBus bus = new TestBus();
        Path logs = Files.createDirectories(directory.resolve("console-only"));
        Path latest = logs.resolve("latest.log");
        Files.writeString(latest, "previous run");
        KuudraLogConfiguration configuration = new KuudraLogConfiguration(KuudraLogLevel.INFO, false, false);

        try (KuudraLogSession ignored = KuudraLog.openSession(logs, bus, configuration)) {
            bus.publish(SystemEvent.of("app.running", Map.of()));
        }

        assertEquals("previous run", Files.readString(latest));
        try (var files = Files.list(logs)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".log.gz")));
        }
    }

    private static String unzip(Path archive) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class TestBus implements SystemEventBus {
        private final CopyOnWriteArrayList<Consumer<SystemEvent>> listeners = new CopyOnWriteArrayList<>();
        @Override public AutoCloseable subscribe(Consumer<SystemEvent> listener) { listeners.add(listener); return () -> listeners.remove(listener); }
        @Override public void publish(SystemEvent event) { listeners.forEach(listener -> listener.accept(event)); }
    }
}
