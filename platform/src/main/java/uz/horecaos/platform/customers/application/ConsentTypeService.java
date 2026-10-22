package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.ConsentTypeRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.DefaultConsentType;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;

/**
 * The tenant-wide consent-purpose registry (Settings 10.11, ADR 0109).
 *
 * <p>{@code customer.consent_decisions} has recorded a decision's {@code
 * purpose} as free text since V0017, and {@code customer-detail-pane.ts}'s own
 * doc names the fallout: three different spellings of "marketing" were live at
 * once before the console settled on one. This service is the missing type
 * registry 5.2b's own doc says does not exist. It is a reference catalogue, and
 * {@link ConsentService#record} does not refuse a purpose that is not in it: that
 * needs legal's answer on the lawful basis per purpose (ADR 0029's open input). Until
 * then {@link ConsentPurposeCheck} can <em>report</em> a decision recorded under an
 * unregistered purpose, behind a flag that defaults off.
 *
 * <p><strong>A purpose is worded per locale (ADR 0149, V0683).</strong> Its name is a
 * map by locale, kept in {@code customer.consent_type_translations}, so a tenant whose
 * brands offer a fourth language has purposes worded in it, and the console picks the
 * wording for the language it is read in, then the tenant's default, then any. The
 * three legacy columns are still written for the platform triple for one release.
 */
@Service
public class ConsentTypeService {

    /**
     * Seeded once per tenant, on the first read that finds no registry yet --
     * never at migration time, because a migration cannot enumerate tenants
     * that do not exist yet, and never on tenant onboarding, because that
     * pipeline is a shared hot path several other waves touch concurrently.
     * The two codes are the ones actually in use today:
     * {@code MarketingEligibility}'s default campaign purpose and {@code
     * TermsAcceptanceService.PURPOSE}. The SendPulse import's own historical
     * {@code "MARKETING"} spelling (singular, no suffix) is a known,
     * pre-existing inconsistency this registry does not paper over — folding
     * it in is that importer's own cleanup, not this registry's job.
     *
     * <p>Each is worded in all three languages the platform launched with; a language a
     * tenant adds later is worded by the tenant, not seeded.
     */
    static final List<DefaultConsentType> DEFAULTS = List.of(
            new DefaultConsentType(
                    "MARKETING_PROMOTIONS",
                    wording("Маркетинговые рассылки", "Marketing xabarnomalari", "Marketing messages"),
                    "Promotional messages and campaigns sent by SMS, Telegram, or push.",
                    true),
            new DefaultConsentType(
                    "TERMS_OF_SERVICE",
                    wording("Условия обслуживания", "Xizmat ko'rsatish shartlari", "Terms of service"),
                    "Acceptance of the storefront's published terms.",
                    false),
            // ADR 0159, Decision 6: the customer's tick, at brand scope, that lets the words of one review
            // appear on the restaurant's public page without their name. Policy version 1; the wording is
            // product's and legal's to confirm (open input 2). V0543 inserts the same row for every tenant
            // that already has a registry, because this bootstrap runs only for a tenant with none.
            new DefaultConsentType(
                    "review.public_display",
                    wording(
                            "Показать мой отзыв на странице ресторана без моего имени",
                            "Sharhimni restoran sahifasida ismimsiz ko'rsatish",
                            "Show my review on the restaurant's page without my name"),
                    "The customer agrees that the words of one review may appear on the restaurant's public "
                            + "page, without their name, number or order.",
                    false));

    private static Map<String, String> wording(String ru, String uzLatn, String en) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LocalizedLabels.RU, ru);
        labels.put(LocalizedLabels.UZ_LATN, uzLatn);
        labels.put(LocalizedLabels.EN, en);
        return labels;
    }

    /** A purpose with its name in every language it has one in. */
    public record ConsentTypeView(ConsentTypeRow row, Map<String, String> labels) {}

    private final JdbcConsentTypeStore store;
    private final Clock clock;
    private final BrandLocaleLookup brandLocales;

    @Autowired
    public ConsentTypeService(JdbcConsentTypeStore store, Clock clock, BrandLocaleLookup brandLocales) {
        this.store = store;
        this.clock = clock;
        this.brandLocales = brandLocales;
    }

    /** A service that treats every tenant as sitting on the platform locale fallback, for callers with no brand-locale port. */
    public ConsentTypeService(JdbcConsentTypeStore store, Clock clock) {
        this(store, clock, BrandLocaleLookup.platformFallback());
    }

    /**
     * Every purpose this tenant has defined, with its name in every language it has one in. Bootstraps the code-owned
     * defaults on a tenant's first read rather than requiring a migration to have guessed every tenant that would ever exist.
     */
    @Transactional
    public List<ConsentTypeView> list(UUID tenantId, ActorRef actor) {
        if (!store.hasAny(tenantId)) {
            store.seedDefaults(tenantId, DEFAULTS, systemSubject(actor), clock.instant());
        }
        Map<UUID, Map<String, String>> translations = store.translationsForTenant(tenantId);
        return store.list(tenantId).stream()
                .map(row -> new ConsentTypeView(row, merged(row, translations.getOrDefault(row.id(), Map.of()))))
                .toList();
    }

    /**
     * The languages the registry is read in for this tenant: the union of its brands' supported locales, default first
     * (ADR 0149 Decision 4). A menu for choosing a fallback order, not a constraint: a language outside it that a purpose
     * already carries is still returned.
     */
    @Transactional(readOnly = true)
    public TenantLocaleSet localeSet(UUID tenantId) {
        return brandLocales.tenantLocaleSet(tenantId);
    }

    /**
     * Whether a decision under this code is a decision about a purpose the tenant has registered: an active purpose of the
     * registry, or, for a tenant whose registry has not been bootstrapped yet, one of the code-owned defaults it would get.
     */
    @Transactional(readOnly = true)
    public boolean isRegistered(UUID tenantId, String code) {
        if (store.hasAny(tenantId)) {
            return store.isRegistered(tenantId, code);
        }
        return DEFAULTS.stream().anyMatch(candidate -> candidate.code().equals(code));
    }

    /**
     * The translations table wins; the three legacy columns fill a platform-triple locale it has no row for, so a row written
     * by a build that predates V0683 (or by a caller that only knew the columns) still reads in its three languages.
     */
    private static Map<String, String> merged(ConsentTypeRow row, Map<String, String> translations) {
        Map<String, String> labels = new LinkedHashMap<>(translations);
        putIfAbsentNonBlank(labels, LocalizedLabels.RU, row.labelRu());
        putIfAbsentNonBlank(labels, LocalizedLabels.UZ_LATN, row.labelUz());
        putIfAbsentNonBlank(labels, LocalizedLabels.EN, row.labelEn());
        return labels;
    }

    private static void putIfAbsentNonBlank(
            Map<String, String> labels, String locale, @org.jspecify.annotations.Nullable String value) {
        if (value != null && !value.isBlank()) {
            labels.putIfAbsent(locale, value);
        }
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
