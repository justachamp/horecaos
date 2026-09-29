package uz.horecaos.platform.support;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A logback {@link ListAppender} whose {@code list} can be read while other
 * threads are still logging.
 *
 * <p>{@code ListAppender} keeps its events in a plain {@code ArrayList}. A test
 * that attaches it to the <em>root</em> logger (to prove that no line at any
 * level carries a secret) captures every thread in the Spring context, not just
 * the test's own -- the scheduler threads, the connection pool -- and asserting
 * over {@code lines.list} while one of them logs throws
 * {@link java.util.ConcurrentModificationException} from inside AssertJ.
 * That failed shard 3 of the first sharded CI run, on a test that had passed
 * for weeks, because the shards changed which classes share a JVM's timing.
 *
 * <p>Use this class wherever a test captures the root logger or any logger a
 * background thread may write to. Appends copy the list, which is cheap for the
 * hundreds of events one request produces; a test that captures a whole suite's
 * worth of output should filter in the appender instead.
 */
public final class ThreadSafeListAppender extends ListAppender<ILoggingEvent> {

    public ThreadSafeListAppender() {
        this.list = new CopyOnWriteArrayList<>();
    }
}
