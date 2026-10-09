package uz.horecaos.platform.observability;

import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Puts {@link SensitiveQueryTurboFilter} in front of the logging system once the application
 * context is up (ADR 0029, ADR 0028).
 *
 * <p>After Spring Boot has configured logging, not instead of it: the logging system is
 * configured before any bean exists and reset when it is, so a filter added from outside the
 * context would be lost, and one that came with a replacement {@code logback-spring.xml} would
 * bring a replacement set-up with it. Idempotent, because every cached test context and every
 * refresh runs this against the same JVM-wide logger context.
 */
@Component
class LogRedactionInstaller implements InitializingBean {

    static final String FILTER_NAME = "sensitive-query-redaction";

    @Override
    public void afterPropertiesSet() {
        install(LoggerFactory.getILoggerFactory());
    }

    /**
     * @return whether the filter is in place afterwards; false only when the logging backend is
     *     not logback, which has no turbo filters to add to
     */
    static boolean install(ILoggerFactory factory) {
        if (!(factory instanceof LoggerContext context)) {
            return false;
        }
        synchronized (context) {
            boolean present =
                    context.getTurboFilterList().stream().anyMatch(SensitiveQueryTurboFilter.class::isInstance);
            if (!present) {
                SensitiveQueryTurboFilter filter = new SensitiveQueryTurboFilter();
                filter.setName(FILTER_NAME);
                filter.setContext(context);
                filter.start();
                context.addTurboFilter(filter);
            }
        }
        return true;
    }
}
