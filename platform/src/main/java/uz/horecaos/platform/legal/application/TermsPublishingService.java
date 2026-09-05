package uz.horecaos.platform.legal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.legal.domain.TermsLocale;
import uz.horecaos.platform.legal.domain.TermsVersion;
import uz.horecaos.platform.legal.domain.TermsVersionSummary;
import uz.horecaos.platform.legal.infrastructure.persistence.JdbcTermsStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a brand's terms of service (ADR 0068).
 *
 * <p><strong>A version is never edited, only superseded.</strong> The same
 * argument {@link uz.horecaos.platform.audit.application.ApprovalPolicyService#author}
 * makes for a threshold: a customer accepted specific words at a specific
 * time, and rewriting them under an acceptance already recorded in
 * {@code customer.consent_decisions} would make that acceptance evidence of
 * nothing. Publishing inserts the next {@code version} row; nothing here ever
 * issues an {@code UPDATE} against {@code legal.terms_versions} or its
 * contents, matching the append-only grant V0160 gives the application role.
 */
@Service
public class TermsPublishingService {

    /** Generous, not arbitrary: long enough for a real terms document, bounded so a paste of the wrong file is refused rather than stored. */
    private static final int MAXIMUM_BODY_LENGTH = 200_000;

    private final JdbcTermsStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public TermsPublishingService(JdbcTermsStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** The version currently in force for this brand, or empty if the tenant has never published one. */
    @Transactional(readOnly = true)
    public Optional<TermsVersion> current(UUID tenantId, UUID brandId) {
        return store.current(tenantId, brandId);
    }

    /** One specific historical version, so an operator can open what an earlier publish actually said. */
    @Transactional(readOnly = true)
    public Optional<TermsVersion> version(UUID tenantId, UUID brandId, int version) {
        return store.version(tenantId, brandId, version);
    }

    /** Every version this brand has published, newest first. */
    @Transactional(readOnly = true)
    public List<TermsVersionSummary> history(UUID tenantId, UUID brandId) {
        return store.history(tenantId, brandId);
    }

    /**
     * Publishes the next version.
     *
     * @param contentsByLocale keyed by {@link TermsLocale#tag()}; a tenant may
     *                         author fewer than all three languages, but must
     *                         author at least one — publishing nothing is not
     *                         a version, it is a no-op dressed as one
     * @param note an operator-supplied reason, folded into the ADR 0027 audit
     *             fact; a blank note still produces a fact, because the act of
     *             publishing is itself the reason when nobody types a longer one
     */
    @Transactional
    public TermsVersion publish(
            UUID tenantId, UUID brandId, Map<String, String> contentsByLocale, ActorRef actor, @Nullable String note) {

        Map<String, String> normalized = normalize(contentsByLocale);
        Instant now = clock.instant();
        int version = store.nextVersion(tenantId, brandId);
        UUID id = UUID.randomUUID();
        String publishedBy = actor.subject();

        try {
            store.insert(id, tenantId, brandId, version, publishedBy, now, normalized);
        } catch (DataIntegrityViolationException concurrentPublish) {
            // uq_terms_version. Two operators publishing at once would otherwise
            // silently produce a version whose number the other one also holds.
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another version of these terms was published concurrently; re-read and retry");
        }

        ResourceScope scope = ResourceScope.brand(tenantId, brandId);
        String reason = (note == null || note.isBlank()) ? "Published terms of service version " + version : note;
        audit.record(AuditFact.of("legal.terms.published", AuditClass.BUSINESS)
                .by(actor)
                .at(scope)
                .target("TermsVersion", id)
                .because(reason)
                .changed(Map.of("version", version, "locales", normalized.keySet()))
                .usingCapability(Capability.TERMS_MANAGE.code())
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());

        return new TermsVersion(id, tenantId, brandId, version, normalized, publishedBy, now);
    }

    /** Trims, drops blanks, rejects an unknown locale or an oversized body, and requires at least one entry. */
    private Map<String, String> normalize(Map<String, String> contentsByLocale) {
        if (contentsByLocale == null || contentsByLocale.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Publishing requires text for at least one language");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contentsByLocale.entrySet()) {
            TermsLocale locale = TermsLocale.parse(entry.getKey())
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "\"" + entry.getKey() + "\" is not one of the supported locales " + TermsLocale.tags()));
            String body = entry.getValue() == null ? "" : entry.getValue().strip();
            if (body.isEmpty()) {
                // An operator clearing a field is dropping that language from this
                // version, not publishing an empty document in it.
                continue;
            }
            if (body.length() > MAXIMUM_BODY_LENGTH) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        locale.tag() + " text exceeds the maximum length of " + MAXIMUM_BODY_LENGTH + " characters");
            }
            normalized.put(locale.tag(), body);
        }
        if (normalized.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Publishing requires text for at least one language");
        }
        return normalized;
    }
}
