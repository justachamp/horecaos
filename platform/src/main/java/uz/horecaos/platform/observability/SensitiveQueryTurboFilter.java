package uz.horecaos.platform.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * Masks {@linkplain SensitiveQueryRedactor what a query string carries} in every log line, for
 * every appender and every encoder (ADR 0029, ADR 0028).
 *
 * <p>A turbo filter rather than a pattern converter because it sits in front of all of them. A
 * converter registered for {@code %m} protects the console pattern and nothing else: not a file
 * appender, not the JSON encoder somebody switches on for the log aggregator, not a test's
 * appender. Spring Boot also rebuilds the pattern configuration itself on start, so a converter
 * needs a replacement {@code logback-spring.xml} for the whole logging set-up, and with it the
 * chance to lose a behaviour of Boot's own (the file appender, structured output) that nobody
 * meant to change. This is installed beside whatever configuration is there ({@link
 * LogRedactionInstaller}) and changes none of it.
 *
 * <p><strong>How a line is masked rather than dropped.</strong> A turbo filter can only answer
 * yes or no, so for a line that needs masking it answers {@link FilterReply#DENY} and hands the
 * appenders a masked copy of the same event: same logger, level, marker, thread and throwable,
 * the message already formatted. The copy goes to {@link Logger#callAppenders} and so does not
 * pass through the filters again. Every other line, which is every line that carries no
 * sensitive parameter, is answered {@link FilterReply#NEUTRAL} having cost one {@code indexOf}.
 *
 * <p><strong>What it looks at.</strong> The message and its arguments, formatted once. At INFO and
 * above only text arguments and the message template are examined, so no object's {@code
 * toString} is called an extra time on a hot path; at DEBUG and TRACE every argument is, because
 * that is where an HTTP client's {@code "{} {}", id, request} puts a URL and where a person
 * deliberately turned detail on. Lines the level would drop are never touched. A throwable's
 * message and stack trace are <strong>not</strong> rewritten: an exception whose message is a
 * request URI is for the code that builds it to keep clean (see {@code ProviderHttpClient}), and
 * copying a throwable to edit it would change its identity for every consumer.
 *
 * <p>The copy is built with this class as the logging call site, so a pattern that prints the
 * caller's class or line ({@code %caller}, {@code %class}) shows the filter for a masked line.
 * Nothing in this code base prints either.
 */
public final class SensitiveQueryTurboFilter extends TurboFilter {

    @Override
    public FilterReply decide(
            @Nullable Marker marker,
            Logger logger,
            @Nullable Level level,
            @Nullable String format,
            @Nullable Object @Nullable [] params,
            @Nullable Throwable throwable) {
        // A null format is an isDebugEnabled()-style question, not a line: never answer it with an event.
        if (format == null || level == null || !level.isGreaterOrEqual(logger.getEffectiveLevel())) {
            return FilterReply.NEUTRAL;
        }
        if (!mayCarryAQuery(format, params, level)) {
            return FilterReply.NEUTRAL;
        }
        FormattingTuple formatted = MessageFormatter.arrayFormat(format, params);
        String message = formatted.getMessage();
        String masked = SensitiveQueryRedactor.redact(message);
        if (masked == null || masked.equals(message)) {
            return FilterReply.NEUTRAL;
        }
        Throwable cause = throwable != null ? throwable : formatted.getThrowable();
        LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, level, masked, cause, null);
        if (marker != null) {
            event.addMarker(marker);
        }
        logger.callAppenders(event);
        return FilterReply.DENY;
    }

    /** The cheap test that keeps the formatting off every line that cannot possibly need it. */
    private static boolean mayCarryAQuery(String format, @Nullable Object @Nullable [] params, Level level) {
        if (looksLikeAQuery(format)) {
            return true;
        }
        if (params == null) {
            return false;
        }
        boolean deep = level.toInt() <= Level.DEBUG_INT;
        for (Object param : params) {
            if (param instanceof CharSequence text) {
                if (looksLikeAQuery(text)) {
                    return true;
                }
            } else if (param != null && deep) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeAQuery(CharSequence text) {
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '=' || (c == '%' && i + 1 < length && text.charAt(i + 1) == '3')) {
                return true;
            }
        }
        return false;
    }
}
