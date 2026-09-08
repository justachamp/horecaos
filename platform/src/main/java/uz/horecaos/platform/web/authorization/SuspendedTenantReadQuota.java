package uz.horecaos.platform.web.authorization;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rations what a suspended tenant may read (ADR 0078).
 *
 * <p>Three requests per endpoint per rolling ninety days. The allowance exists
 * because a suspended tenant keeps read access and read access, uncounted, is
 * an export: a listing endpoint called in a loop hands over the same rows a
 * download button would.
 *
 * <p>Counted per {@code (tenant, endpoint)} and deliberately not per principal.
 * A quota per person would be bought off by inviting a colleague.
 */
@Component
public class SuspendedTenantReadQuota {

    /** Three, per the owner's decision of 2026-09-08. */
    public static final int ALLOWANCE = 3;

    /** "Three months", as ninety days — a window needs a length, not a calendar. */
    public static final Duration WINDOW = Duration.ofDays(90);

    private final JdbcClient jdbc;
    private final Clock clock;

    public SuspendedTenantReadQuota(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records one read and says whether it was within the allowance.
     *
     * <p>Writes first, then counts, and both in one statement's worth of
     * ordering: the count includes the row just written, so the third read
     * returns {@code true} and the fourth returns {@code false}. Counting before
     * writing would let two concurrent requests both see two and both proceed.
     *
     * <p>{@code REQUIRES_NEW} because the caller is an interceptor running
     * before the handler's own transaction exists, and because a refusal must
     * still leave the attempt on record — a suspended tenant probing an endpoint
     * it is out of allowance for is exactly what somebody will want to see
     * later.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(UUID tenantId, String endpoint, String principalSubject) {
        jdbc.sql("""
                INSERT INTO tenant.suspended_read_log (id, tenant_id, endpoint, principal_subject, read_at)
                VALUES (:id, :tenantId, :endpoint, :subject, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("endpoint", endpoint)
                .param("subject", principalSubject)
                .param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .update();

        Long used = jdbc.sql("""
                SELECT count(*) FROM tenant.suspended_read_log
                 WHERE tenant_id = :tenantId AND endpoint = :endpoint AND read_at > :windowStart
                """)
                .param("tenantId", tenantId)
                .param("endpoint", endpoint)
                .param("windowStart", clock.instant().minus(WINDOW).atOffset(java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();

        return used <= ALLOWANCE;
    }
}
