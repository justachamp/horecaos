package uz.horecaos.platform.iam.application.terminals;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * Stamps a device's «last seen» (ADR 0155, Decision 6): any authenticated request from a device principal, at most once a
 * minute per device in each node's memory, and one bounded {@code UPDATE}.
 *
 * <p>No heartbeat endpoint exists. The signal is the device actually working -- a board poll, a keypad touch, a
 * kiosk reading its configuration -- not a ping, so a healthy idle kiosk that polls once a minute and a stuck one are
 * told apart by how long since, not by a flag.
 *
 * <p>Never fails the request it observes: a stamp that cannot be written is logged and dropped, because a screen that
 * stops working because it could not say it was working is the wrong way round.
 */
@Component
public class DeviceSeenRecorder {

    private static final Logger log = LoggerFactory.getLogger(DeviceSeenRecorder.class);

    static final int MAX_BUILD_LENGTH = 64;

    private final JdbcClient jdbc;
    private final Cache stamped;
    private final Clock clock;

    public DeviceSeenRecorder(JdbcClient jdbc, CacheManager caches, Clock clock) {
        this.jdbc = jdbc;
        Cache cache = caches.getCache(CacheRegistry.IAM_DEVICE_LAST_SEEN.cacheName());
        this.stamped = cache != null ? cache : new org.springframework.cache.concurrent.ConcurrentMapCache("unused");
        this.clock = clock;
    }

    public void stamp(UUID deviceId, @Nullable String build) {
        try {
            if (stamped.putIfAbsent(deviceId, Boolean.TRUE) != null) {
                return;
            }
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            jdbc.sql("""
                    UPDATE iam.device_principals
                       SET last_seen_at = :now, last_seen_build = COALESCE(:build, last_seen_build)
                     WHERE id = :id AND (last_seen_at IS NULL OR last_seen_at < :now - interval '30 seconds')
                    """)
                    .param("now", now)
                    .param("build", clean(build))
                    .param("id", deviceId)
                    .update();
        } catch (RuntimeException failure) {
            log.warn("Could not stamp a device's last-seen time (device {})", deviceId, failure);
        }
    }

    /** A reported build is a label the device chose: bounded, printable, and nothing more. */
    static @Nullable String clean(@Nullable String build) {
        if (build == null || build.isBlank()) {
            return null;
        }
        String trimmed = build.strip();
        if (trimmed.length() > MAX_BUILD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_BUILD_LENGTH);
        }
        return trimmed.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
