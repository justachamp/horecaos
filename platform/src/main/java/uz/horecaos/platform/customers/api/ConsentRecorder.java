package uz.horecaos.platform.customers.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Recording that a customer agreed to something (ADR 0015), for a caller that
 * already knows exactly what was shown and needs the platform's one
 * append-only evidence store to remember it — never a second, module-local
 * acceptance table.
 *
 * <p>Deliberately narrower than {@link uz.horecaos.platform.customers.application.ConsentService}
 * itself, which stays internal: this port only ever grants, on behalf of the
 * customer whose own action this is, and takes plain strings rather than
 * {@code ConsentService}'s application-layer enums so a caller outside this
 * module never needs to import them. A withdrawal, an import-sourced
 * decision, or a support agent acting on somebody's behalf are different
 * acts with different evidence, and stay behind {@code ConsentService}
 * itself, used only from inside {@code customers}.
 *
 * <p>Distinct from {@link ConsentDirectory}, whose own Javadoc is emphatic
 * that reading a decision must never become forming one. This is the write
 * side that {@code ConsentDirectory} exists to keep every other module away
 * from — a legal module recording a terms-of-service acceptance is not
 * "another module deciding" in the sense that doc warns against, because the
 * decision was the customer's own and already made; this is only where it is
 * written down.
 */
public interface ConsentRecorder {

    /**
     * Records that the customer granted consent for one purpose, right now.
     *
     * @param brandId null for a tenant-wide purpose, set for a brand-specific one
     * @param channel null when the purpose is not channel-specific
     * @param policyVersion identifies exactly what the customer agreed to —
     *                       the caller's own versioning scheme, opaque to this
     *                       port. Once recorded it is never rewritten: a later
     *                       change to whatever this string names must not
     *                       alter what this row says was accepted.
     * @param source one of the sources {@code ConsentService.Source} declares
     *               ({@code STOREFRONT}, {@code SUPPORT_AGENT}, {@code IMPORT},
     *               {@code MIGRATION}, {@code API}) — a caller acting for a
     *               customer's own storefront action passes {@code STOREFRONT}
     * @param evidenceReference an opaque pointer to the accepted content
     *                          (a document id, a version number), or null
     * @return the new {@code consent_decisions} row id
     */
    UUID recordGrant(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            @Nullable String channel,
            String policyVersion,
            String source,
            @Nullable String evidenceReference);
}
