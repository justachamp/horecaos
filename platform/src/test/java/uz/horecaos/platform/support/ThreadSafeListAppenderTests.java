package uz.horecaos.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ThreadSafeListAppenderTests {

    private static final int EVENTS = 20_000;

    @Test
    void assertingOverTheCapturedEventsWhileAnotherThreadLogsNeverThrows() throws Exception {
        ThreadSafeListAppender appender = new ThreadSafeListAppender();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ThreadSafeListAppenderTests.class);
        CountDownLatch writerDone = new CountDownLatch(1);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        AtomicBoolean sawAnError = new AtomicBoolean();

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < EVENTS; i++) {
                    appender.doAppend(
                            new LoggingEvent(Logger.class.getName(), logger, Level.INFO, "event " + i, null, null));
                }
            } finally {
                writerDone.countDown();
            }
        });
        Thread reader = new Thread(() -> {
            try {
                // The exact shape of the failing assertion: a stream over the live list.
                while (writerDone.getCount() > 0) {
                    if (appender.list.stream().anyMatch((ILoggingEvent event) -> event.getLevel() == Level.ERROR)) {
                        sawAnError.set(true);
                    }
                }
            } catch (Throwable failure) {
                readerFailure.set(failure);
            }
        });
        reader.start();
        writer.start();
        writer.join(TimeUnit.SECONDS.toMillis(60));
        reader.join(TimeUnit.SECONDS.toMillis(60));

        assertThat(readerFailure.get()).as("reading the list while it grows").isNull();
        assertThat(sawAnError).as("only INFO events were logged").isFalse();
        assertThat(appender.list).hasSize(EVENTS);
    }

    @Test
    void itBehavesLikeAListAppenderForASingleThread() {
        ThreadSafeListAppender appender = new ThreadSafeListAppender();
        appender.start();
        Logger logger = (Logger) LoggerFactory.getLogger(ThreadSafeListAppenderTests.class);
        logger.addAppender(appender);
        try {
            logger.info("one");
            logger.warn("two");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly("one", "two");
    }
}
