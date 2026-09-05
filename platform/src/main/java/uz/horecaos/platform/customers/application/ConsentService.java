package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ConsentHistoryRow;

/**
 * Consent as an append-only record (ADR 0015, ADR 0029).
 *
 * <p>Never an updatable flag. The obligation is to prove what a person agreed to,
 * when, under which policy version, and through which channel — and an UPDATE
 * destroys precisely that evidence. The grants on
 * {@code customer.consent_decisions} withhold UPDATE and DELETE so a future bug
 * cannot rewrite it either.
 *
 * <p>Absence is not consent. A purpose with no decision is treated as withheld,
 * which is why {@link #hasConsent} returns false rather than defaulting to true.
 */
@Service
public class ConsentService implements ConsentDirectory, ConsentRecorder {

    private final JdbcCustomerStore store;
    private final Clock clock;

    public ConsentService(JdbcCustomerStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Records a decision.
     *
     * @param brandId null for a tenant-wide purpose, set for a brand-specific one
     * @param channel null when the purpose is not channel-specific
     * @param decidedAt when the person decided, which is not always when we heard
     *                  about it — an import carries the original date, and using
     *                  the import time instead would misstate the record
     */
    @Transactional
    public UUID record(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            Decision decision,
            String policyVersion,
            Source source,
            @Nullable String evidenceReference,
            @Nullable Instant decidedAt) {

        UUID id = UUID.randomUUID();
        store.insertConsentDecision(
                id,
                tenantId,
                accountId,
                brandId,
                purpose,
                channel,
                decision.name(),
                policyVersion,
                source.name(),
                evidenceReference,
                decidedAt == null ? clock.instant() : decidedAt);
        return id;
    }

    /**
     * Whether a purpose is currently permitted.
     *
     * <p>False when there is no decision at all. Notifications and marketing must
     * ask this rather than assume, because "we never asked" and "they said yes"
     * are the two states a default-true would merge.
     */
    @Transactional(readOnly = true)
    public boolean hasConsent(UUID tenantId, UUID accountId, UUID brandId, String purpose, String channel) {
        return store.currentConsent(tenantId, accountId, brandId, purpose, channel)
                .map(row -> "GRANTED".equals(row.decision()))
                .orElse(false);
    }

    /** The current decision with its evidence, for showing a customer or an auditor. */
    @Transactional(readOnly = true)
    public Optional<CurrentConsent> current(
            UUID tenantId, UUID accountId, UUID brandId, String purpose, String channel) {
        return store.currentConsent(tenantId, accountId, brandId, purpose, channel)
                .map(row -> new CurrentConsent(
                        purpose,
                        channel,
                        Decision.valueOf(row.decision()),
                        row.policyVersion(),
                        row.decidedAt(),
                        Source.valueOf(row.source())));
    }

    /**
     * The ADR 0020 view of the same record.
     *
     * <p>Narrower than {@link #current} on purpose: a module deciding whether to
     * send a message needs the decision, its policy version, and its date, and
     * has no business holding the evidence reference or the channel a person was
     * asked through.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ConsentState> consentFor(
            UUID tenantId, UUID accountId, @Nullable UUID brandId, String purpose, @Nullable String channel) {
        return store.currentConsent(tenantId, accountId, brandId, purpose, channel)
                .map(row -> new ConsentState("GRANTED".equals(row.decision()), row.policyVersion(), row.decidedAt()));
    }

    /**
     * {@link ConsentRecorder}'s narrower write, for a caller outside this
     * module that already knows exactly what the customer agreed to.
     *
     * <p>Always {@link Decision#GRANTED} through this port — a module reaching
     * across a boundary to record a customer's own affirmative action has no
     * business withdrawing one, importing one, or backdating one; those stay
     * {@link #record} calls made from inside {@code customers} itself.
     * {@code decidedAt} is always now, for the same reason: this is the
     * customer acting in this request, not an import replaying history.
     */
    @Override
    @Transactional
    public UUID recordGrant(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            String policyVersion,
            String source,
            @Nullable String evidenceReference) {
        return record(
                tenantId,
                accountId,
                brandId,
                purpose,
                channel,
                Decision.GRANTED,
                policyVersion,
                Source.valueOf(source),
                evidenceReference,
                null);
    }

    /** The full decision history. This is what a subject-access request produces. */
    @Transactional(readOnly = true)
    public List<ConsentHistoryRow> history(UUID tenantId, UUID accountId) {
        return store.consentHistory(tenantId, accountId);
    }

    public enum Decision {
        GRANTED,
        WITHDRAWN
    }

    public enum Source {
        STOREFRONT,
        SUPPORT_AGENT,
        IMPORT,
        MIGRATION,
        API
    }

    public record CurrentConsent(
            String purpose,
            String channel,
            Decision decision,
            String policyVersion,
            Instant decidedAt,
            Source source) {}
}
