package uz.horecaos.platform.legal.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.ConsentDirectory.ConsentState;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.legal.domain.EffectiveTerms;
import uz.horecaos.platform.legal.domain.PlatformDefaultTerms;
import uz.horecaos.platform.legal.domain.TermsLocale;
import uz.horecaos.platform.legal.domain.TermsVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What the storefront shows for terms of service, and recording that a
 * customer accepted it (ADR 0067, the owner's 2026-08-30 decision).
 *
 * <p><strong>Reuses ADR 0015's consent store instead of a second acceptance
 * table.</strong> {@code customer.consent_decisions} already is an
 * append-only record of a purpose, a policy version, a decision, and when it
 * was made, with the same non-rewritable guarantee (insert/select only, no
 * update or delete) a terms acceptance needs. Building a parallel table here
 * would duplicate exactly that guarantee rather than reuse it.
 *
 * <p><strong>Locale is part of the accepted version, not a detail beside
 * it.</strong> {@link EffectiveTerms#policyVersionLabel()} carries the
 * language shown — {@code "v3:ru"}, not {@code "v3"} — because a translation
 * can say something different from the words it stands for, and a customer
 * who read the Uzbek text agreed to the Uzbek text, not to a Russian version
 * that happens to share its number. Switching app language after accepting
 * therefore asks again, which is the conservative reading and a deliberate
 * choice this wave's report names explicitly.
 *
 * <p><strong>A locale the tenant has not authored falls back to the platform
 * default for that same language</strong> — never to a different language the
 * tenant did write in. Showing an English-reading customer the tenant's
 * Uzbek text because that is what exists would be unreadable to them; showing
 * them the neutral platform default in English is not, and it says as much
 * (see {@link PlatformDefaultTerms}'s own closing paragraph).
 */
@Service
public class TermsAcceptanceService {

    /** {@code customer.consent_decisions.purpose} for every row this service writes or reads. */
    public static final String PURPOSE = "TERMS_OF_SERVICE";

    /** {@code customer.consent_decisions.source} — this service only ever acts on the storefront's own behalf. */
    private static final String SOURCE_STOREFRONT = "STOREFRONT";

    private final TermsPublishingService publishing;
    private final ConsentDirectory consentDirectory;
    private final ConsentRecorder consentRecorder;
    private final Clock clock;

    public TermsAcceptanceService(
            TermsPublishingService publishing,
            ConsentDirectory consentDirectory,
            ConsentRecorder consentRecorder,
            Clock clock) {
        this.publishing = publishing;
        this.consentDirectory = consentDirectory;
        this.consentRecorder = consentRecorder;
        this.clock = clock;
    }

    /** The document a storefront should render right now, for this brand and locale. */
    @Transactional(readOnly = true)
    public EffectiveTerms effective(UUID tenantId, UUID brandId, String requestedLocale, String brandDisplayName) {
        String locale = requireLocale(requestedLocale);
        Optional<TermsVersion> current = publishing.current(tenantId, brandId);
        if (current.isPresent()) {
            TermsVersion version = current.get();
            String authoredBody = version.contentsByLocale().get(locale);
            if (authoredBody != null) {
                return new EffectiveTerms(
                        versionLabel(version.version(), locale), locale, authoredBody, false, version.version());
            }
        }
        return new EffectiveTerms(
                defaultLabel(locale), locale, PlatformDefaultTerms.forLocale(locale, brandDisplayName), true, null);
    }

    /**
     * Records that the signed-in customer accepted whatever is currently in
     * force, in the language they were shown.
     *
     * <p>Always accepts the <em>current</em> effective document rather than a
     * version number the caller supplies — the same reason
     * {@code ApprovalDecisionService} never lets a client name which policy
     * version it is deciding under. A client-supplied version would let a
     * stale screen record acceptance of words that are no longer served.
     */
    @Transactional
    public AcceptanceRecord accept(
            UUID tenantId, UUID brandId, UUID accountId, String requestedLocale, String brandDisplayName) {
        EffectiveTerms effective = effective(tenantId, brandId, requestedLocale, brandDisplayName);
        Instant now = clock.instant();
        UUID decisionId = consentRecorder.recordGrant(
                tenantId,
                accountId,
                brandId,
                PURPOSE,
                null,
                effective.policyVersionLabel(),
                SOURCE_STOREFRONT,
                evidenceReferenceFor(effective));
        return new AcceptanceRecord(decisionId, effective.policyVersionLabel(), now);
    }

    /**
     * Whether this customer's most recent acceptance matches what is
     * currently in force, in the requested language.
     *
     * <p>Compares labels, not merely "has this customer ever accepted
     * anything" — a customer who accepted version 2 is not treated as having
     * accepted version 3, and one who accepted the Russian text is not
     * treated as having accepted the same version's Uzbek translation.
     */
    @Transactional(readOnly = true)
    public AcceptanceStatus status(
            UUID tenantId, UUID brandId, UUID accountId, String requestedLocale, String brandDisplayName) {
        EffectiveTerms effective = effective(tenantId, brandId, requestedLocale, brandDisplayName);
        Optional<ConsentState> state = consentDirectory.consentFor(tenantId, accountId, brandId, PURPOSE, null);
        boolean accepted = state.filter(ConsentState::granted)
                .map(ConsentState::policyVersion)
                .filter(effective.policyVersionLabel()::equals)
                .isPresent();
        return new AcceptanceStatus(
                accepted,
                effective.policyVersionLabel(),
                state.map(ConsentState::policyVersion).orElse(null),
                state.map(ConsentState::decidedAt).orElse(null));
    }

    private static String requireLocale(String requestedLocale) {
        return TermsLocale.parse(requestedLocale)
                .map(TermsLocale::tag)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "locale must be one of " + TermsLocale.tags() + ", got \"" + requestedLocale + "\""));
    }

    private static String versionLabel(int version, String locale) {
        return "v" + version + ":" + locale;
    }

    private static String defaultLabel(String locale) {
        return "default-v" + PlatformDefaultTerms.VERSION + ":" + locale;
    }

    private static String evidenceReferenceFor(EffectiveTerms effective) {
        return effective.isPlatformDefault() ? "platform-default" : "terms-version:" + effective.documentVersion();
    }

    /** @param acceptedAt when this call recorded the decision — the row's own {@code decided_at}, not a later read of it */
    public record AcceptanceRecord(UUID decisionId, String policyVersionLabel, Instant acceptedAt) {}

    /**
     * @param currentVersionLabel what is in force right now, for the caller's own comparison or display
     * @param lastAcceptedLabel null if this customer never accepted anything for this brand and purpose
     * @param lastAcceptedAt null under the same condition as {@link #lastAcceptedLabel}
     */
    public record AcceptanceStatus(
            boolean accepted,
            String currentVersionLabel,
            @Nullable String lastAcceptedLabel,
            @Nullable Instant lastAcceptedAt) {}
}
