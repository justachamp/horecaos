package uz.horecaos.platform.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Every line the JVM logs while it is open, captured the way it is in a shared test JVM.
 *
 * <p><strong>The bridge is part of the capture, deliberately.</strong> The first
 * {@code @SpringBootTest} in a JVM makes Spring Boot's logging system install
 * {@code SLF4JBridgeHandler} and a {@code LevelChangePropagator}, after which everything written
 * through {@code java.util.logging} or {@code System.Logger} -- the JDK's own
 * {@code com.sun.net.httpserver} among it -- arrives in logback, and raising the root level to
 * {@code ALL} raises the JUL level with it. Run alone, a class has neither, so a line written
 * through that path never reaches the appender and a test that asserts "nothing sensitive was
 * logged" passes on a laptop and fails on the CI runner that happened to run it after a Spring
 * context (2026-10-09, main 9f04ec80d, shard 2: {@code GeocodePortContractTests}). A capture that
 * depends on what ran before it is not evidence, so this installs the same two things itself
 * when no earlier test has, and puts back exactly what it installed.
 *
 * <p>The root level is read before it is changed and restored to that, so a capture does not leave
 * every later test in the JVM logging at {@code ALL}.
 */
public final class JvmLogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previousRootLevel;
    private final boolean bridgeInstalledHere;
    private final @Nullable LevelChangePropagator propagatorInstalledHere;

    private JvmLogCapture(
            Level previousRootLevel, boolean bridgeInstalledHere, @Nullable LevelChangePropagator propagator) {
        this.previousRootLevel = previousRootLevel;
        this.bridgeInstalledHere = bridgeInstalledHere;
        this.propagatorInstalledHere = propagator;
    }

    /** Starts capturing everything at {@code ALL}, through the same JUL bridge Spring Boot installs. */
    public static JvmLogCapture start() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        boolean bridge = !SLF4JBridgeHandler.isInstalled();
        if (bridge) {
            SLF4JBridgeHandler.install();
        }
        LevelChangePropagator propagator = null;
        if (context.getCopyOfListenerList().stream().noneMatch(LevelChangePropagator.class::isInstance)) {
            propagator = new LevelChangePropagator();
            propagator.setResetJUL(true);
            propagator.setContext(context);
            propagator.start();
            context.addListener(propagator);
        }
        Logger root = rootLogger();
        JvmLogCapture capture = new JvmLogCapture(root.getLevel(), bridge, propagator);
        capture.appender.setContext(context);
        capture.appender.start();
        root.setLevel(Level.ALL);
        root.addAppender(capture.appender);
        return capture;
    }

    /** A consistent copy: background threads keep appending while an assertion iterates. */
    public List<ILoggingEvent> snapshot() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    @Override
    public void close() {
        Logger root = rootLogger();
        root.detachAppender(appender);
        root.setLevel(previousRootLevel);
        appender.stop();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        if (propagatorInstalledHere != null) {
            context.removeListener(propagatorInstalledHere);
            propagatorInstalledHere.stop();
        }
        if (bridgeInstalledHere) {
            SLF4JBridgeHandler.uninstall();
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
