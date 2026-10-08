package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.TemplateRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.VersionRow;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;

/**
 * Authoring, approving, and resolving template wording (ADR 0020).
 *
 * <p>The rule this service exists to enforce is that a missing translation fails
 * while somebody is writing copy, not at 22:00 when a customer is waiting for a
 * confirmation. A version is a set of rows — one per locale — and
 * {@link #activate} refuses unless every locale the template's brand serves is present
 * ({@link #requiredLocales}, ADR 0149: it used to be every locale the platform has).
 * The check cannot be a database constraint because it is a statement about a set
 * of rows and a CHECK sees one at a time.
 *
 * <p>The second rule is that a template can only name variables its schema
 * declares. That is checked when a draft is saved, so a typo is a refused draft
 * rather than a customer reading "Заказ {{orderNumbr}} принят".
 *
 * <p><strong>Every write leaves an audit fact</strong> (ADR 0027, staff row {@code 9.3a}) in the
 * transaction that made it: {@code notification.template.created}, {@code
 * notification.template.version_added} and {@code notification.template.version_activated}. The
 * wording is what customers read as the business speaking, so «who changed the confirmation SMS
 * on Friday» has to be answerable. The fact names the version and the size of each language and
 * not the text: the version rows already keep the text, addressable by the number the fact
 * carries, and an audit row is the wrong place for a second copy of marketing copy.
 */
@Service
public class NotificationTemplateService {

    private static final TypeReference<Map<String, String>> SCHEMA_TYPE = new TypeReference<>() {};

    private final JdbcTemplateStore templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final BrandLocaleLookup brandLocales;

    /** A service with no tenancy to ask: every brand counts the platform's content tier (tests, tools). */
    public NotificationTemplateService(
            JdbcTemplateStore templates,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor) {
        this(templates, objectMapper, clock, audit, currentActor, BrandLocaleLookup.platformFallback());
    }

    @Autowired
    public NotificationTemplateService(
            JdbcTemplateStore templates,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor,
            BrandLocaleLookup brandLocales) {
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
        this.brandLocales = brandLocales;
    }

    /**
     * The languages a version of this template must be written in before it can be saved or
     * activated (ADR 0020, as ADR 0149 changed what it counts): <em>the languages the template's
     * brand serves</em>, not every language the platform has.
     *
     * <p>The rule exists to keep a customer from getting nothing, and a brand-scoped rule prevents
     * that just as well without making a Tashkent restaurant author Georgian. A brand that has
     * configured no set counts the platform's content tier, exactly as the editors already read it;
     * a tenant-wide template (no brand of its own) serves every brand, so it counts the union of
     * the tenant's. Only languages the platform can <em>send</em> in count: a language a brand may
     * hold in its content but that is not live in the messages tier cannot be authored here, so it
     * cannot be required. A brand serving none of the live ones owes the fallback, never nothing.
     */
    public List<MessageLocale> requiredLocales(UUID tenantId, @Nullable UUID brandId) {
        TenantLocaleSet served = brandId == null
                ? brandLocales.tenantLocaleSet(tenantId)
                : brandLocales.brandLocaleSet(tenantId, brandId);
        List<MessageLocale> required = served.locales().stream()
                .map(MessageLocale::parse)
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        return required.isEmpty() ? List.of(MessageLocale.FALLBACK) : required;
    }

    /**
     * Registers a template with no variant dimension — every fulfilment mode,
     * every channel source. Delegates to the eight-argument overload below
     * with both null, so every caller that predates gap-map row {@code
     * 10.9a} is unaffected.
     */
    @Transactional
    public UUID createTemplate(
            UUID tenantId,
            @Nullable UUID brandId,
            String templateKey,
            NotificationClass notificationClass,
            NotificationChannel channel,
            @Nullable String consentPurpose) {
        return createTemplate(tenantId, brandId, templateKey, notificationClass, channel, consentPurpose, null, null);
    }

    /**
     * Registers a template.
     *
     * @param brandId null for the tenant's default wording, set for a brand that
     *                words it differently
     * @param consentPurpose the ADR 0015 purpose this template needs. Required for
     *                       an optional or marketing class and refused for the
     *                       others, because a receipt gated on a promotional
     *                       opt-in is the failure this parameter prevents
     * @param fulfillmentMode null for every fulfilment mode (a wildcard), set to
     *                        narrow this wording to one of delivery, pickup or
     *                        dine-in (gap-map row 10.9a) — a second variant for
     *                        the same key, brand and channel is a distinct
     *                        template row, not a field on an existing one
     * @param channelSource null for every channel, set to narrow this wording to
     *                      one inbound channel (storefront, Telegram, a call
     *                      centre, an aggregator…)
     */
    @Transactional
    public UUID createTemplate(
            UUID tenantId,
            @Nullable UUID brandId,
            String templateKey,
            NotificationClass notificationClass,
            NotificationChannel channel,
            @Nullable String consentPurpose,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource) {

        if (notificationClass.requiresConsent() && (consentPurpose == null || consentPurpose.isBlank())) {
            throw new IllegalArgumentException(notificationClass + " needs a consent purpose to check against");
        }
        if (!notificationClass.requiresConsent() && consentPurpose != null) {
            // Refused rather than ignored. A purpose recorded on a required
            // transactional template reads as though the message is gated on it,
            // and the next person to touch this will make it so.
            throw new IllegalArgumentException(
                    notificationClass + " does not resolve consent and must not name a purpose");
        }

        UUID id = UUID.randomUUID();
        templates.insertTemplate(
                id,
                tenantId,
                brandId,
                templateKey,
                notificationClass.name(),
                channel.name(),
                consentPurpose,
                fulfillmentMode == null ? null : fulfillmentMode.name(),
                channelSource == null ? null : channelSource.name(),
                clock.instant());
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("templateKey", templateKey);
        created.put("notificationClass", notificationClass.name());
        created.put("channel", channel.name());
        created.put("consentPurpose", consentPurpose);
        created.put("fulfillmentMode", fulfillmentMode == null ? null : fulfillmentMode.name());
        created.put("channelSource", channelSource == null ? null : channelSource.name());
        // A creation has no prior state: every field's "before" is null.
        recordAudit(
                AuditFact.of("notification.template.created", AuditClass.BUSINESS),
                tenantId,
                brandId,
                id,
                1,
                "Notification template created",
                Map.of(),
                created);
        return id;
    }

    /**
     * Saves one draft version, in every locale at once.
     *
     * <p>All three locales in one call rather than three calls, so "a version" is
     * a thing an author either has or does not. Saving them one at a time would
     * make a half-translated version a legitimate intermediate state, and
     * intermediate states are what get activated by accident.
     *
     * @return the version number, allocated by the store in one statement so two
     *         authors saving at once cannot collide
     */
    @Transactional
    public int addVersion(
            UUID tenantId,
            UUID brandId,
            UUID templateId,
            Map<MessageLocale, Wording> wordings,
            Map<String, String> variablesSchema) {

        // Read for its side effect: a template id from another tenant, or a
        // sibling brand's own template, must not be given a version here — the
        // composite foreign key alone would let the insert through on a
        // matching id, and the endpoint's BRAND-scoped capability only proves
        // the caller was authorised for the brand in the URL.
        TemplateRow owned = requireOwnedByBrand(tenantId, brandId, templateId);
        // ADR 0091, decided 2026-09-11: a new SMS wording for a gateway that
        // moderates texts waits for the gateway's approval before it can send.
        boolean awaitsGateway =
                NotificationChannel.SMS.name().equals(owned.channel()) && templates.smsWordingAwaitsGateway(tenantId);

        List<MessageLocale> required = requiredLocales(tenantId, owned.brandId());
        List<MessageLocale> missing = required.stream()
                .filter(locale -> !wordings.containsKey(locale))
                .toList();
        if (!missing.isEmpty()) {
            throw new IncompleteTranslationException(
                    "A version needs every locale this brand serves before it can be saved; missing " + missing);
        }

        Set<String> declared = variablesSchema.keySet();
        int versionNumber = templates.nextVersionNumber(tenantId, templateId);
        Instant now = clock.instant();
        String schemaJson = objectMapper.writeValueAsString(variablesSchema);

        // Every wording the author sent is kept, the required ones and any extra the platform can send
        // in: a version authored in four languages for a brand that serves two is a draft that is
        // ready the day the brand adds a third, not a rejected one.
        for (Map.Entry<MessageLocale, Wording> entry : wordings.entrySet()) {
            MessageLocale locale = entry.getKey();
            Wording wording = entry.getValue();
            // Both halves are checked, because a subject is as capable of naming
            // a variable that does not exist as a body is.
            TemplateRenderer.validate(wording.subject(), declared);
            TemplateRenderer.validate(wording.body(), declared);

            templates.insertVersion(
                    UUID.randomUUID(),
                    tenantId,
                    templateId,
                    versionNumber,
                    locale.tag(),
                    wording.subject(),
                    wording.body(),
                    schemaJson,
                    contentHashOf(locale, wording),
                    awaitsGateway,
                    now);
        }

        Map<String, Object> added = new LinkedHashMap<>();
        added.put("templateKey", owned.templateKey());
        added.put("channel", owned.channel());
        added.put("versionNumber", versionNumber);
        // Not «awaiting…»: ChangeDocuments redacts any key containing «tin».
        added.put("gatewayReviewRequired", awaitsGateway);
        for (Map.Entry<MessageLocale, Wording> entry : wordings.entrySet()) {
            added.put(
                    "characters." + entry.getKey().tag(),
                    entry.getValue().body().length());
        }
        // The version is new: there was no draft of this number to diff against.
        recordAudit(
                AuditFact.of("notification.template.version_added", AuditClass.BUSINESS),
                tenantId,
                owned.brandId(),
                templateId,
                owned.version(),
                "Notification template wording saved as a new version",
                Map.of(),
                added);

        return versionNumber;
    }

    /**
     * Makes a version the one that is sent.
     *
     * <p>Refuses unless every locale of that version exists and is a draft. This is
     * the visible failure ADR 0020 asks for: a tenant that translated two of three
     * languages is stopped here, with the missing one named, rather than
     * discovering it from a customer.
     */
    @Transactional
    public void activate(UUID tenantId, UUID brandId, UUID templateId, int versionNumber, String approvedBy) {
        TemplateRow template = requireOwnedByBrand(tenantId, brandId, templateId);

        List<VersionRow> versions = templates.versions(tenantId, templateId, versionNumber);
        // A row in a language the platform no longer sends in (a deactivated one) neither satisfies
        // nor blocks the rule: it is parsed away, not thrown on.
        List<MessageLocale> present = versions.stream()
                .map(row -> MessageLocale.parse(row.locale()))
                .flatMap(Optional::stream)
                .toList();
        List<MessageLocale> missing = requiredLocales(tenantId, template.brandId()).stream()
                .filter(locale -> !present.contains(locale))
                .toList();

        if (!missing.isEmpty()) {
            throw new IncompleteTranslationException(
                    "Version %d of this template cannot be activated: %s missing".formatted(versionNumber, missing));
        }

        int activated = templates.activateVersion(tenantId, templateId, versionNumber, approvedBy, clock.instant());
        if (activated != versions.size()) {
            // Some locale of this version was not a draft, which means another
            // operator activated or superseded it between the read above and this
            // update. Refusing is right: half of a version being live is worse
            // than the activation not happening.
            throw new IllegalStateException("Version %d was changed by someone else; %d of %d locales activated"
                    .formatted(versionNumber, activated, versions.size()));
        }
        if (!templates.markTemplateActive(tenantId, templateId, versionNumber, template.version(), clock.instant())) {
            throw new IllegalStateException("The template was changed by someone else");
        }
        recordAudit(
                AuditFact.of("notification.template.version_activated", AuditClass.BUSINESS),
                tenantId,
                template.brandId(),
                templateId,
                template.version() + 1,
                "Notification template version activated",
                activeVersionOf(template, template.activeVersion()),
                activeVersionOf(template, versionNumber));
    }

    // ------------------------------------------------------------------ audit

    /**
     * One fact per write, in the caller's transaction. The reason is a plain statement of the
     * action: the console has no field for one, and ADR 0027 refuses a user-initiated fact
     * without it.
     *
     * @param brandId null for the tenant's default wording
     */
    private void recordAudit(
            AuditFact.Builder fact,
            UUID tenantId,
            @Nullable UUID brandId,
            UUID templateId,
            int version,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(fact.by(ActorRef.user(currentActor.get().subject(), null))
                .at(brandId == null ? ResourceScope.tenant(tenantId) : ResourceScope.brand(tenantId, brandId))
                .target("notification.template", templateId)
                .targetVersion((long) version)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(templateId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** The template recognisable by its key and channel, with the version that is sent. */
    private static Map<String, Object> activeVersionOf(TemplateRow template, @Nullable Integer activeVersion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("templateKey", template.templateKey());
        snapshot.put("channel", template.channel());
        snapshot.put("activeVersion", activeVersion);
        return snapshot;
    }

    // -------------------------------------------------------------- resolution

    /**
     * The wording this message will use, or the reason there is none — with no
     * fulfilment mode or channel source to narrow by. Delegates to the seven
     * -argument overload below with both null, for a caller with no order to
     * ask ({@code CampaignTelegramDeliveryService}'s audience-wide send).
     */
    @Transactional(readOnly = true)
    public Resolution resolve(
            UUID tenantId, UUID brandId, String templateKey, NotificationChannel channel, MessageLocale locale) {
        return resolve(tenantId, brandId, templateKey, channel, locale, null, null);
    }

    /**
     * The wording this message will use, or the reason there is none.
     *
     * <p>Two lookups rather than one join, so the caller can tell "this tenant has
     * no confirmation template" from "it has one, but not in the language this
     * customer reads". Those are different problems for the tenant and a join
     * returns the same empty result for both.
     *
     * @param fulfillmentMode gap-map row 10.9a: the order's own fulfilment mode,
     *                        when this message is about one, so the most specific
     *                        variant resolves ahead of the tenant or brand default
     * @param channelSource the order's own inbound channel, under the same
     *                       condition
     */
    @Transactional(readOnly = true)
    public Resolution resolve(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationChannel channel,
            MessageLocale locale,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource) {

        // ADR 0149, Decision 4: a customer whose language the brand does not serve is written to in
        // the brand's default, and where that wording is missing too, in the registry's fallback. The
        // brand-scoped "required" rule is checked when a version is activated; this is its send-time
        // partner, which is what lets a brand add a language after a template went live without that
        // template going silent for the customers who read it.
        TenantLocaleSet served = brandLocales.brandLocaleSet(tenantId, brandId);
        List<String> chain = new ArrayList<>();
        chain.add(served.locales().contains(locale.tag()) ? locale.tag() : served.defaultLocale());
        chain.add(served.defaultLocale());
        chain.add(MessageLocale.FALLBACK.tag());

        Resolution last = Resolution.noTemplate();
        for (String tag : chain.stream().distinct().toList()) {
            Optional<MessageLocale> candidate = MessageLocale.parse(tag);
            if (candidate.isEmpty()) {
                continue;
            }
            last = resolveExact(
                    tenantId, brandId, templateKey, channel, candidate.get(), fulfillmentMode, channelSource);
            if (last.isFound() || last.outcome() == Resolution.Outcome.NO_ACTIVE_TEMPLATE) {
                return last;
            }
        }
        return last;
    }

    /**
     * The wording in exactly this language, with no fallback: found, or why it is not. What a caller
     * that wants to know which languages a template <em>has</em> asks (the campaign composer's
     * per-language bodies), as against {@link #resolve}, which finds a customer something to read.
     */
    @Transactional(readOnly = true)
    public Resolution resolveExact(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationChannel channel,
            MessageLocale locale,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource) {

        Optional<TemplateRow> template = templates.activeTemplate(
                tenantId,
                brandId,
                templateKey,
                channel.name(),
                fulfillmentMode == null ? null : fulfillmentMode.name(),
                channelSource == null ? null : channelSource.name());
        if (template.isEmpty()) {
            return Resolution.noTemplate();
        }
        TemplateRow row = template.get();
        Integer activeVersion = row.activeVersion();
        if (activeVersion == null) {
            return Resolution.noTemplate();
        }

        return templates
                .version(tenantId, row.id(), activeVersion, locale.tag())
                .filter(version -> "ACTIVE".equals(version.status()))
                .map(version -> Resolution.found(row, version))
                .orElseGet(Resolution::noLocale);
    }

    /** The declared variable names of a stored version. */
    public Set<String> declaredVariables(VersionRow version) {
        return declaredVariablesSchema(version).keySet();
    }

    /**
     * The full declared schema of a stored version — name to declared type —
     * so a reader (the editor's own {@code GET}) sees what an author actually
     * declared rather than only the names {@link #declaredVariables} keeps.
     */
    public Map<String, String> declaredVariablesSchema(VersionRow version) {
        return objectMapper.readValue(version.variablesSchemaJson(), SCHEMA_TYPE);
    }

    @Transactional(readOnly = true)
    public List<TemplateRow> forBrand(UUID tenantId, UUID brandId) {
        return templates.templatesForBrand(tenantId, brandId);
    }

    @Transactional(readOnly = true)
    public List<VersionRow> versions(UUID tenantId, UUID brandId, UUID templateId, int versionNumber) {
        // Read for its side effect, same as addVersion: neither another
        // tenant's template id nor a sibling brand's own template may answer
        // here.
        requireOwnedByBrand(tenantId, brandId, templateId);
        return templates.versions(tenantId, templateId, versionNumber);
    }

    /**
     * Every version of a template, every locale — the read a create-only
     * editor never had a caller for. The caller groups by
     * {@code versionNumber}; ordered newest version first.
     */
    @Transactional(readOnly = true)
    public List<VersionRow> allVersions(UUID tenantId, UUID brandId, UUID templateId) {
        // Read for its side effect, same as addVersion: a template id from
        // another tenant, or a sibling brand's own template, must not answer
        // here either.
        TemplateRow owned = requireOwnedByBrand(tenantId, brandId, templateId);
        // owned itself is unused past this point — the read above exists only to
        // 404 a foreign template id before its versions are listed.
        return templates.allVersionsOfTemplate(tenantId, owned.id());
    }

    /**
     * @throws IllegalArgumentException {@code templateId} does not belong to
     *         this tenant, or names a template that is a *different* brand's
     *         own override — the endpoint's BRAND-scoped capability only
     *         proves the caller was authorised for the brand in the URL, not
     *         that {@code templateId} belongs to it, so every method reached
     *         from that endpoint must re-check here. A tenant-wide default
     *         ({@code brandId() == null}) is visible to every brand, matching
     *         {@link #resolve}'s own precedence.
     */
    private TemplateRow requireOwnedByBrand(UUID tenantId, UUID brandId, UUID templateId) {
        TemplateRow owned = templates
                .template(tenantId, templateId)
                .orElseThrow(
                        () -> new IllegalArgumentException("No template " + templateId + " belongs to this tenant"));
        UUID ownedBrandId = owned.brandId();
        if (ownedBrandId != null && !ownedBrandId.equals(brandId)) {
            throw new IllegalArgumentException("No template " + templateId + " belongs to this brand");
        }
        return owned;
    }

    private String contentHashOf(MessageLocale locale, Wording wording) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("locale", locale.tag());
        parts.put("subject", wording.subject() == null ? "" : wording.subject());
        parts.put("body", wording.body());
        return ContentHashes.ofVariables(parts);
    }

    /**
     * One locale's text.
     *
     * @param subject null on a channel that has no subject, which SMS does not.
     *                Null rather than blank so "this channel has no subject" and
     *                "the author left it empty" stay distinguishable
     */
    public record Wording(@Nullable String subject, String body) {

        public Wording {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("A template version needs a body");
            }
        }
    }

    /** Either the wording, or which of the two ways it was missing. */
    public record Resolution(
            @Nullable TemplateRow template, @Nullable VersionRow version, Outcome outcome) {

        public enum Outcome {
            FOUND,
            NO_ACTIVE_TEMPLATE,
            NO_TEMPLATE_FOR_LOCALE
        }

        static Resolution found(TemplateRow template, VersionRow version) {
            return new Resolution(template, version, Outcome.FOUND);
        }

        static Resolution noTemplate() {
            return new Resolution(null, null, Outcome.NO_ACTIVE_TEMPLATE);
        }

        static Resolution noLocale() {
            return new Resolution(null, null, Outcome.NO_TEMPLATE_FOR_LOCALE);
        }

        public boolean isFound() {
            return outcome == Outcome.FOUND;
        }
    }

    /** A version does not exist in every locale HorecaOS sends in. */
    public static class IncompleteTranslationException extends RuntimeException {

        public IncompleteTranslationException(String message) {
            super(message);
        }
    }
}
