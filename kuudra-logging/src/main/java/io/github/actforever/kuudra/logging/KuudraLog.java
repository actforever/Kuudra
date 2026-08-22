package io.github.actforever.kuudra.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.ILoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * Kuudra core's private Logback context.
 *
 * <p>It intentionally does not use {@code LoggerFactory.getLogger}: a Kuudra
 * runtime embedded in kuudra-web must not inherit Spring Boot's root logger,
 * appenders or level changes.</p>
 */
public final class KuudraLog {
    private static final LoggerContext CONTEXT = createContext();

    private KuudraLog() { }

    public static org.slf4j.Logger getLogger(Class<?> type) {
        return CONTEXT.getLogger(type.getName());
    }

    /** Exposed for diagnostics and isolation tests; callers must not stop it. */
    public static ILoggerFactory context() {
        return CONTEXT;
    }

    /** Opens one kernel log lifecycle, truncating latest.log and archiving it on close. */
    public static KuudraLogSession openSession(Path logsDirectory, io.github.actforever.kuudra.api.SystemEventBus events) throws IOException {
        Path directory = logsDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path latest = directory.resolve("latest.log");
        Logger logger = CONTEXT.getLogger("io.github.actforever.kuudra.session." + UUID.randomUUID());
        logger.setAdditive(true);
        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file = new FileAppender<>();
        file.setContext(CONTEXT);
        file.setName("KUUDRA_FILE_" + UUID.randomUUID());
        file.setFile(latest.toString());
        file.setAppend(false);
        file.setEncoder(encoder(CONTEXT, "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n"));
        file.start();
        logger.addAppender(file);
        AutoCloseable subscription = events.subscribe(event -> write(logger, event));
        return new Session(directory, latest, logger, file, subscription);
    }

    private static LoggerContext createContext() {
        LoggerContext context = new LoggerContext();
        context.setName("kuudra-core");
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        context.start();
        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("KUUDRA_CONSOLE");
        appender.setEncoder(encoder(context, "%d{HH:mm:ss.SSS} %boldCyan([KUUDRA]) %highlight(%-5level) %boldWhite(%msg)\n"));
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(appender);
        return context;
    }

    private static PatternLayoutEncoder encoder(LoggerContext context, String pattern) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context); encoder.setPattern(pattern); encoder.start(); return encoder;
    }

    private static void write(Logger logger, io.github.actforever.kuudra.api.SystemEvent event) {
        String message = event.type() + (event.data().isEmpty() ? "" : " " + event.data());
        if (event.type().contains("failed")) logger.error(message);
        else if (event.type().contains("rejected") || event.type().contains("cancel")) logger.warn(message);
        else logger.info(message);
    }

    private static final class Session implements KuudraLogSession {
        private final Path directory; private final Path latest; private final Logger logger;
        private final FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file; private final AutoCloseable subscription; private boolean closed;
        private Session(Path directory, Path latest, Logger logger,
                        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file, AutoCloseable subscription) {
            this.directory = directory; this.latest = latest; this.logger = logger; this.file = file; this.subscription = subscription;
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true;
            try { subscription.close(); } catch (Exception error) { throw new IllegalStateException("Failed to unsubscribe Kuudra logging", error); }
            logger.detachAppender(file); file.stop();
            try {
                if (!Files.exists(latest)) return;
                String date = LocalDate.now(ZoneId.systemDefault()).toString();
                int sequence = 1; Path archive;
                do { archive = directory.resolve(date + "-" + sequence++ + ".log.gz"); } while (Files.exists(archive));
                try (var input = Files.newInputStream(latest); var output = new GZIPOutputStream(Files.newOutputStream(archive))) {
                    input.transferTo(output);
                }
                Files.delete(latest);
            } catch (IOException error) {
                throw new IllegalStateException("Failed to archive Kuudra latest.log", error);
            }
        }
    }
}
