package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.ConsentTypeRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.DefaultConsentType;

/**
 * The tenant-wide consent-purpose registry (Settings 10.11, ADR 0109).
 *
 * <p>{@code customer.consent_decisions} has recorded a decision's {@code
 * purpose} as free text since V0017, and {@code customer-detail-pane.ts}'s own
 * doc names the fallout: three different spellings of "marketing" were live at
 * once before the console settled on one. This service is the missing type
 * registry 5.2b's own doc says does not exist. It is deliberately a reference
 * catalogue, not an enforcement point — {@link ConsentService#record} still
 * accepts whatever {@code purpose} its caller passes, exactly as it does
 * today. Constraining every consent-decision writer to a code drawn from this
 * table is a larger, separate change this wave does not make.
 */
@Service
public class ConsentTypeService {

    /**
     * Seeded once per tenant, on the first read that finds no registry yet —
     * never at migration time, because a migration cannot enumerate tenants
     * that do not exist yet, and never on tenant onboarding, because that
     * pipeline is a shared hot path several other waves touch concurrently.
     * The two codes are the ones actually in use today:
     * {@code MarketingEligibility}'s default campaign purpose and {@code
     * TermsAcceptanceService.PURPOSE}. The SendPulse import's own historical
     * {@code "MARKETING"} spelling (singular, no suffix) is a known,
     * pre-existing inconsistency this registry does not paper over — folding
     * it in is that importer's own cleanup, not this registry's job.
     */
    static final List<DefaultConsentType> DEFAULTS = List.of(
            new DefaultConsentType(
                    "MARKETING_PROMOTIONS",
                    "Маркетинговые рассылки",
                    "Marketing xabarnomalari",
                    "Marketing messages",
                    "Promotional messages and campaigns sent by SMS, email, Telegram, or push.",
                    true),
            new DefaultConsentType(
                    "TERMS_OF_SERVICE",
                    "Условия обслуживания",
                    "Xizmat ko'rsatish shartlari",
                    "Terms of service",
                    "Acceptance of the storefront's published terms.",
                    false),
            // ADR 0159, Decision 6: the customer's tick, at brand scope, that lets the words of one review
            // appear on the restaurant's public page without their name. Policy version 1; the wording is
            // product's and legal's to confirm (open input 2). V0543 inserts the same row for every tenant
            // that already has a registry, because this bootstrap runs only for a tenant with none.
            new DefaultConsentType(
                    "review.public_display",
                    "Показать мой отзыв на странице ресторана без моего имени",
                    "Sharhimni restoran sahifasida ismimsiz ko'rsatish",
                    "Show my review on the restaurant's page without my name",
                    "The customer agrees that the words of one review may appear on the restaurant's public "
                            + "page, without their name, number or order.",
                    false));

    private final JdbcConsentTypeStore store;
    private final Clock clock;

    public ConsentTypeService(JdbcConsentTypeStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Every purpose this tenant has defined. Bootstraps the code-owned
     * defaults on a tenant's first read rather than requiring a migration to
     * have guessed every tenant that would ever exist.
     */
    @Transactional
    public List<ConsentTypeRow> list(UUID tenantId, ActorRef actor) {
        if (!store.hasAny(tenantId)) {
            store.seedDefaults(tenantId, DEFAULTS, systemSubject(actor), clock.instant());
        }
        return store.list(tenantId);
    }

    /**
     * The bootstrap is attributed to a system actor rather than whichever
     * operator's read happened to trigger it — a default catalogue is a
     * platform fact, not something that operator chose to create.
     */
    private static String systemSubject(ActorRef actor) {
        return actor.type() == ActorRef.Type.SYSTEM_JOB ? actor.subject() : "system:consent-type-bootstrap";
    }
}
