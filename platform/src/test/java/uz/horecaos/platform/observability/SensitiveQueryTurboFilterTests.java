package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uz.horecaos.platform.support.JvmLogCapture;

/**
 * The filter against a logger context of its own, so what is proved is the filter and not what
 * else happens to be configured in the JVM, plus one run end to end through the real JDK HTTP
 * server and the real JUL bridge -- the path that carried the key and the address into a CI log
 * on 2026-10-09 (ADR 0029, ADR 0028).
 */
class SensitiveQueryTurboFilterTests {

    private static final String KEY = "server-key-must-never-be-logged";
    private static final String CANARY = "canary-address";
    private static final String REQUEST_LINE =
            "GET /1.x/?apikey=" + KEY + "&geocode=Zaglushka%20" + CANARY + "&format=json HTTP/1.1";
    private static final String MASKED_REQUEST_LINE =
            "GET /1.x/?apikey=[redacted]&geocode=[redacted]&format=json HTTP/1.1";

    private LoggerContext context;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void aLoggerContextWithTheFilterInFront() {
        context = new LoggerContext();
        LogRedactionInstaller.install(context);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.ALL);
        root.addAppender(appender);
        logger = context.getLogger("com.example.HttpClient");
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    // ------------------------------------------------------------------ what is masked

    @Test
    @DisplayName("a preformatted line, as the JUL bridge delivers one, reaches the appender masked and once")
    void aPreformattedLineIsMaskedOnce() {
        logger.debug("Exchange request line: " + REQUEST_LINE);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent written = appender.list.get(0);
        assertThat(written.getFormattedMessage()).isEqualTo("Exchange request line: " + MASKED_REQUEST_LINE);
        assertThat(written.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(written.getLoggerName()).isEqualTo("com.example.HttpClient");
        assertThat(written.getThreadName()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("a secret that arrives as a {} argument is masked, in a template that holds no secret itself")
    void aTextArgumentIsMasked() {
        logger.info("Sending {}", REQUEST_LINE);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sending " + MASKED_REQUEST_LINE);
    }

    @Test
    @DisplayName("at DEBUG an argument that is an object is looked into too: that is where a client's request hides")
    void anObjectArgumentIsMaskedAtDebug() {
        Object request = new Object() {
            @Override
            public String toString() {
                return REQUEST_LINE;
            }
        };

        logger.debug("{} {}", "exchange-7", request);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("exchange-7 " + MASKED_REQUEST_LINE);
    }

    @Test
    @DisplayName("the throwable, whether passed explicitly or as the last argument, and the marker survive the copy")
    void theThrowableAndTheMarkerSurvive() {
        Marker audit = MarkerFactory.getMarker("AUDIT");
        IllegalStateException explicit = new IllegalStateException("boom");
        IllegalStateException trailing = new IllegalStateException("bang");

        logger.warn(audit, "failed " + REQUEST_LINE, explicit);
        logger.warn("failed {}", REQUEST_LINE, trailing);

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getThrowableProxy().getMessage()).isEqualTo("boom");
        assertThat(appender.list.get(0).getMarkerList()).containsExactly(audit);
        assertThat(appender.list.get(1).getThrowableProxy().getMessage()).isEqualTo("bang");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(KEY).doesNotContain(CANARY));
    }

    // -------------------------------------------------------------- what is left alone

    @Test
    @DisplayName("a line with nothing to mask is written once, untouched, by the ordinary path")
    void anOrdinaryLineIsUntouched() {
        logger.info("order {} paid, total={}", 7, 1200);
        logger.debug("GET /api/orders?status=PAID HTTP/1.1");

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("order 7 paid, total=1200", "GET /api/orders?status=PAID HTTP/1.1");
    }

    @Test
    @DisplayName("a line the level drops is dropped, and asking whether DEBUG is on writes nothing")
    void aDisabledLevelWritesNothing() {
        context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);

        logger.debug(REQUEST_LINE);
        assertThat(logger.isDebugEnabled()).isFalse();

        assertThat(appender.list)
                .as("an event for a level that is off would be a line nobody asked for, and it would carry the "
                        + "secret to a sink at a level that was meant to be quiet")
                .isEmpty();
    }

    @Test
    @DisplayName("asking whether DEBUG is on, with it on, is a question and writes nothing")
    void aQuestionIsNotALine() {
        assertThat(logger.isDebugEnabled()).isTrue();
        assertThat(logger.isTraceEnabled()).isTrue();

        assertThat(appender.list).isEmpty();
    }

    // ---------------------------------------------------------------- the installer

    @Test
    @DisplayName("installing twice leaves one filter, and a backend that is not logback is reported, not an error")
    void theInstallerIsIdempotent() {
        LogRedactionInstaller.install(context);
        LogRedactionInstaller.install(context);

        assertThat(context.getTurboFilterList())
                .filteredOn(SensitiveQueryTurboFilter.class::isInstance)
                .hasSize(1);
        assertThat(LogRedactionInstaller.install(new NOPLoggerFactory())).isFalse();
    }

    @Test
    @DisplayName(
            "the application context installs it into the JVM's logger context, so it is wired and not just written")
    void theApplicationContextInstallsIt() {
        LoggerContext global = (LoggerContext) LoggerFactory.getILoggerFactory();
        boolean alreadyThere =
                global.getTurboFilterList().stream().anyMatch(SensitiveQueryTurboFilter.class::isInstance);
        try (AnnotationConfigApplicationContext spring =
                new AnnotationConfigApplicationContext(LogRedactionInstaller.class)) {
            assertThat(global.getTurboFilterList())
                    .filteredOn(SensitiveQueryTurboFilter.class::isInstance)
                    .hasSize(1);
            assertThat(spring.getBean(LogRedactionInstaller.class)).isNotNull();
        } finally {
            if (!alreadyThere) {
                global.getTurboFilterList().removeIf(SensitiveQueryTurboFilter.class::isInstance);
            }
        }
    }

    // --------------------------------------------------------- end to end, as CI saw it

    @Test
    @DisplayName(
            "the JDK's own HTTP server logging a request at DEBUG through the JUL bridge: the lines are there, the secret is not")
    void theJdkServersAccessLogIsMasked() throws Exception {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        LoggerContext global = (LoggerContext) factory;
        boolean filterWasThere =
                global.getTurboFilterList().stream().anyMatch(SensitiveQueryTurboFilter.class::isInstance);
        LogRedactionInstaller.install(global);
        Logger jdkServer = global.getLogger("com.sun.net.httpserver");
        Level pinned = jdkServer.getLevel();
        jdkServer.setLevel(Level.ALL);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (JvmLogCapture capture = JvmLogCapture.start()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1.x/?apikey=" + KEY
                    + "&geocode=Zaglushka%20" + CANARY + "&format=json");

            HttpResponse<Void> reply = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding());

            assertThat(reply.statusCode()).isEqualTo(200);
            List<String> written = capture.snapshot().stream()
                    .map(event -> event.getLoggerName() + " | " + event.getFormattedMessage())
                    .toList();
            assertThat(written)
                    .as("the server's request line and reply line must be in the capture, or this proves nothing")
                    .anyMatch(
                            line -> line.startsWith(
                                    "com.sun.net.httpserver | Exchange request line: GET /1.x/?apikey=[redacted]&geocode=[redacted]"))
                    .anyMatch(line ->
                            line.startsWith("com.sun.net.httpserver | GET /1.x/?apikey=[redacted]&geocode=[redacted]")
                                    && line.matches(".*\\[200\\s+OK].*"));
            assertThat(written).noneMatch(line -> line.contains(KEY) || line.contains(CANARY));
        } finally {
            server.stop(0);
            jdkServer.setLevel(pinned);
            if (!filterWasThere) {
                global.getTurboFilterList().removeIf(SensitiveQueryTurboFilter.class::isInstance);
            }
        }
    }
}
