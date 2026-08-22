package io.github.actforever.kuudra.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.ILoggerFactory;

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

    private static LoggerContext createContext() {
        LoggerContext context = new LoggerContext();
        context.setName("kuudra-core");
        context.start();
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{HH:mm:ss.SSS} %-5level [kuudra] %logger{36} - %msg%n");
        encoder.start();
        ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName("KUUDRA_CONSOLE");
        appender.setEncoder(encoder);
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(appender);
        return context;
    }
}
