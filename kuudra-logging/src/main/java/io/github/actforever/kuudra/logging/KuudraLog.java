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
import java.util.Objects;
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

    /** Opens one kernel log lifecycle, replacing the previous latest.log and archiving it on close. */
    public static KuudraLogSession openSession(Path logsDirectory, io.github.actforever.kuudra.api.system.SystemEventBus events) throws IOException {
        return openSession(logsDirectory, events, KuudraLogConfiguration.DEFAULT);
    }

    /** Opens one configured kernel log lifecycle. */
    public static KuudraLogSession openSession(Path logsDirectory, io.github.actforever.kuudra.api.system.SystemEventBus events,
                                               KuudraLogConfiguration configuration) throws IOException {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(configuration, "configuration");
        Path directory = logsDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path latest = directory.resolve("latest.log");
        Logger logger = CONTEXT.getLogger("io.github.actforever.kuudra.session." + UUID.randomUUID());
        logger.setLevel(level(configuration.level()));
        logger.setAdditive(configuration.consoleEnabled());
        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file = null;
        if (configuration.fileEnabled()) {
            Files.deleteIfExists(latest);
            file = new FileAppender<>();
            file.setContext(CONTEXT);
            file.setName("KUUDRA_FILE_" + UUID.randomUUID());
            file.setFile(latest.toString());
            file.setAppend(false);
            file.setEncoder(encoder(CONTEXT, "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %msg%n"));
            file.start();
            logger.addAppender(file);
        }
        AutoCloseable subscription = events.subscribe(event -> write(logger, event));
        return new Session(directory, latest, logger, file, subscription, configuration.fileEnabled());
    }

    private static Level level(KuudraLogLevel level) {
        return switch (level) {
            case TRACE -> Level.TRACE;
            case DEBUG -> Level.DEBUG;
            case INFO -> Level.INFO;
            case WARN -> Level.WARN;
            case ERROR -> Level.ERROR;
            case OFF -> Level.OFF;
        };
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

    private static void write(Logger logger, io.github.actforever.kuudra.api.system.SystemEvent event) {
        if (event.type().equals("plugin.log")) {
            String plugin = "[plugin=" + event.data().get("namespace") + "/" + event.data().get("pluginId") + "] ";
            String message = plugin + event.data().get("message") + fields(event.data().get("fields"));
            String level = Objects.toString(event.data().get("level"), "INFO");
            switch (level) {
                case "TRACE" -> logger.trace(message);
                case "DEBUG" -> logger.debug(message);
                case "WARN" -> logger.warn(message);
                case "ERROR" -> logger.error(message + (event.data().containsKey("error") ? " " + event.data().get("error") : ""));
                default -> logger.info(message);
            }
            return;
        }
        String message = event.type() + (event.data().isEmpty() ? "" : " " + event.data());
        switch (event.level()) {
            case TRACE -> logger.trace(message);
            case DEBUG -> logger.debug(message);
            case INFO -> logger.info(message);
            case WARN -> logger.warn(message);
            case ERROR -> logger.error(message);
            case AUTO -> {
                if (event.type().contains("failed")) logger.error(message);
                else if (event.type().contains("rejected") || event.type().contains("cancel")) logger.warn(message);
                else logger.info(message);
            }
        }
    }

    private static String fields(Object value) {
        return value instanceof java.util.Map<?, ?> fields && !fields.isEmpty() ? " " + fields : "";
    }

    private static final class Session implements KuudraLogSession {
        private final Path directory; private final Path latest; private final Logger logger;
        private final FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file; private final AutoCloseable subscription;
        private final boolean archiveFile; private boolean closed;
        private Session(Path directory, Path latest, Logger logger,
                        FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file, AutoCloseable subscription,
                        boolean archiveFile) {
            this.directory = directory; this.latest = latest; this.logger = logger; this.file = file;
            this.subscription = subscription; this.archiveFile = archiveFile;
        }
        @Override public synchronized void close() {
            if (closed) return; closed = true;
            try { subscription.close(); } catch (Exception error) { throw new IllegalStateException("Failed to unsubscribe Kuudra logging", error); }
            if (file != null) { logger.detachAppender(file); file.stop(); }
            if (!archiveFile) return;
            try {
                if (!Files.exists(latest)) return;
                String date = LocalDate.now(ZoneId.systemDefault()).toString();
                int sequence = 1; Path archive;
                do { archive = directory.resolve(date + "-" + sequence++ + ".log.gz"); } while (Files.exists(archive));
                try (var input = Files.newInputStream(latest); var output = new GZIPOutputStream(Files.newOutputStream(archive))) {
                    input.transferTo(output);
                }
            } catch (IOException error) {
                throw new IllegalStateException("Failed to archive Kuudra latest.log", error);
            }
        }
    }
}
