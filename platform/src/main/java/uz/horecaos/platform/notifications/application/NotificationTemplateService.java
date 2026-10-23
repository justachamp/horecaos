package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.media.api.TenantImageCheck;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;
import uz.horecaos.platform.notifications.domain.PlatformDefaultTemplates;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.ShownVersionRow;
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

    // ADR 0184 gave this service three more rules, each with its decision:
    //
    //   * D19: a SECURITY template cannot be created. The one-time code sentence is the platform's
    //     (customers.api.OtpWording) and nothing ever sends a SECURITY template.
    //   * D20: which template wins is decided in JdbcTemplateStore.activeTemplate, lexicographically:
    //     scope, then variant dimensions, then fulfilment mode over channel source. The platform
    //     default is the last layer, below every tenant and brand template, and applies only when the
    //     tenant has no active template at any scope for the key and channel.
    //   * D21: a version may not declare a variable that is personal in the wrong place (customerPhone,
    //     courierPhone and courierSurname anywhere; customerName outside a customer-addressed class;
    //     courierFirstName outside ORDER_FULFILLING). See NotificationVariableCatalog#refusal.

    private static final TypeReference<Map<String, String>> SCHEMA_TYPE = new TypeReference<>() {};

    private final JdbcTemplateStore templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final BrandLocaleLookup brandLocales;
    private final TenantImageCheck images;

    /** A service with no tenancy to ask: every brand counts the platform's content tier (tests, tools). */
    public NotificationTemplateService(
            JdbcTemplateStore templates,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor) {
        this(templates, objectMapper, clock, audit, currentActor, BrandLocaleLookup.platformFallback());
    }

    /** A service that cannot vouch for a cover image: a push wording with one is refused (tests, tools). */
    public NotificationTemplateService(
            JdbcTemplateStore templates,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor,
            BrandLocaleLookup brandLocales) {
        this(
                templates,
                objectMapper,
                clock,
                audit,
                currentActor,
                brandLocales,
                (tenantId, assetId, ratioWidth, ratioHeight, maximumBytes) -> TenantImageCheck.Verdict.UNKNOWN_ASSET);
    }

    @Autowired
    public NotificationTemplateService(
            JdbcTemplateStore templates,
            ObjectMapper objectMapper,
            Clock clock,
            AuditRecorder audit,
            CurrentActor currentActor,
            BrandLocaleLookup brandLocales,
            TenantImageCheck images) {
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.audit = audit;
        this.currentActor = currentActor;
        this.brandLocales = brandLocales;
        this.images = images;
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

        if (notificationClass == NotificationClass.SECURITY) {
            // ADR 0184, D19. The one-time code sentence is the platform's and is sent by the customers
            // module without a template (OtpWording); a SECURITY template would be wording an operator
            // can write and nothing ever sends, which is the silent failure the old console invited.
            throw new IllegalArgumentException("A SECURITY template cannot be authored: the sentence a customer reads"
                    + " with a one-time code belongs to the platform and is shown read-only (ADR 0184, D19)");
        }
        if (channel == NotificationChannel.EMAIL && notificationClass != NotificationClass.MARKETING) {
            // ADR 0182, D13: the platform's relay carries campaign email and nothing else. Refused when
            // the template is authored, so a tenant is not told only when a customer is waiting.
            throw new IllegalArgumentException(
                    "An EMAIL template is a MARKETING template: the platform sends campaign email only, " + "and not "
                            + notificationClass);
        }
        if (channel == NotificationChannel.PUSH && notificationClass != NotificationClass.MARKETING) {
            // ADR 0182, D14: Web Push carries campaigns and nothing else, for the reason email does.
            throw new IllegalArgumentException(
                    "A PUSH template is a MARKETING template: the platform sends campaign push only, " + "and not "
                            + notificationClass);
        }
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
        return addVersion(tenantId, brandId, templateId, wordings, variablesSchema, false);
    }

    /**
     * Saves one draft version, with the version's «show item prices» setting (ADR 0184).
     *
     * @param showItemPrices whether {@code {{productList}}} follows each item with its line total. One
     *        value for the whole version, written on every locale row. Refused when no wording names
     *        {@code {{productList}}}: a switch that changes nothing is a mistake the author should hear
     *        about while writing
     */
    @Transactional
    public int addVersion(
            UUID tenantId,
            UUID brandId,
            UUID templateId,
            Map<MessageLocale, Wording> wordings,
            Map<String, String> variablesSchema,
            boolean showItemPrices) {

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

        if (NotificationChannel.EMAIL.name().equals(owned.channel())
                || NotificationChannel.PUSH.name().equals(owned.channel())) {
            // An email without a subject is a message every inbox files under «no subject»; its
            // absence is as much a missing translation as a missing body.
            for (Map.Entry<MessageLocale, Wording> entry : wordings.entrySet()) {
                String subject = entry.getValue().subject();
                if (subject == null || subject.isBlank()) {
                    throw new IllegalArgumentException("An %s wording needs a subject in every language; missing for %s"
                            .formatted(owned.channel(), entry.getKey().tag()));
                }
            }
        }

        boolean push = NotificationChannel.PUSH.name().equals(owned.channel());
        for (Map.Entry<MessageLocale, Wording> entry : wordings.entrySet()) {
            checkPushFields(tenantId, push, entry.getKey(), entry.getValue());
        }

        Set<String> declared = variablesSchema.keySet();
        // ADR 0184, D21: a personal value in the wrong place is refused while somebody is writing,
        // not discovered as an empty hole (or a leaked name) when a customer is waiting.
        NotificationVariableCatalog.refusal(
                        NotificationClass.valueOf(owned.notificationClass()), owned.templateKey(), declared)
                .ifPresent(reason -> {
                    throw new TemplateRenderer.TemplateContractException(reason);
                });
        if (showItemPrices
                && wordings.values().stream()
                        .noneMatch(wording ->
                                TemplateRenderer.variablesUsedIn(wording.body()).contains("productList"))) {
            throw new IllegalArgumentException(
                    "«Show item prices» changes only {{productList}}, and no wording in this version uses it");
        }
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
                    contentHashOf(locale, wording, showItemPrices),
                    awaitsGateway,
                    showItemPrices,
                    wording.coverAssetId(),
                    wording.targetPath(),
                    now);
        }

        Map<String, Object> added = new LinkedHashMap<>();
        added.put("templateKey", owned.templateKey());
        added.put("channel", owned.channel());
        added.put("versionNumber", versionNumber);
        // Not «awaiting…»: ChangeDocuments redacts any key containing «tin».
        added.put("gatewayReviewRequired", awaitsGateway);
        added.put("showItemPrices", showItemPrices);
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

    /** The most a push notification's title and body may hold before a browser cuts them off anyway. */
    static final int PUSH_TITLE_LIMIT = 100;

    static final int PUSH_BODY_LIMIT = 400;

    /** The cover is a 3:1 picture, and a notification is not the place for a large one. */
    static final int PUSH_COVER_BYTES = 2 * 1024 * 1024;

    /**
     * A path on the storefront's own origin: one leading slash and not two (that would be a link to
     * somebody else's site), printable ASCII, no spaces, no backslash. Query and fragment are allowed,
     * because a promotion that opens a category with a code is a link like any other.
     */
    private static final java.util.regex.Pattern TARGET_PATH =
            java.util.regex.Pattern.compile("/(?!/)[\\x21-\\x5B\\x5D-\\x7E]{0,198}|/");

    /**
     * What a push wording adds to the text (ADR 0182, D14), checked when it is authored so a marketer
     * hears it from the editor and not from a customer: a title and a body a notification can hold, a
     * cover that is this tenant's own public picture in the 3:1 shape, and a path that stays on the
     * storefront. Any of the last two on another channel is refused, because nothing would show them.
     */
    private void checkPushFields(UUID tenantId, boolean push, MessageLocale locale, Wording wording) {
        if (!push) {
            if (wording.coverAssetId() != null || wording.targetPath() != null) {
                throw new IllegalArgumentException("A cover and a target path belong to a PUSH wording only");
            }
            return;
        }
        String tag = locale.tag();
        if (wording.subject() != null && wording.subject().length() > PUSH_TITLE_LIMIT) {
            throw new IllegalArgumentException(
                    "A push title is at most %d characters; too long for %s".formatted(PUSH_TITLE_LIMIT, tag));
        }
        if (wording.body().length() > PUSH_BODY_LIMIT) {
            throw new IllegalArgumentException(
                    "A push body is at most %d characters; too long for %s".formatted(PUSH_BODY_LIMIT, tag));
        }
        if (wording.targetPath() != null
                && !TARGET_PATH.matcher(wording.targetPath()).matches()) {
            throw new IllegalArgumentException(
                    "A push target is a path on the storefront, starting with a single slash; not valid for " + tag);
        }
        if (wording.coverAssetId() != null) {
            TenantImageCheck.Verdict verdict = images.check(tenantId, wording.coverAssetId(), 3, 1, PUSH_COVER_BYTES);
            if (verdict != TenantImageCheck.Verdict.ACCEPTED) {
                throw new IllegalArgumentException("The push cover for %s cannot be used: %s"
                        .formatted(
                                tag,
                                verdict.name()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .replace('_', ' ')));
            }
        }
    }

    // ---------------------------------------------------- the wordings a dispatcher may broadcast

    /**
     * The SMS wordings of the class {@code COURIER_DISPATCH} that are in force for this brand (ADR 0182,
     * D15): the only wordings a courier broadcast may name.
     *
     * <p>A brand's own template with a key replaces the tenant-wide one with the same key, as everywhere
     * else. A wording is listed once it has an active version, whether or not the gateway has approved
     * it: the dispatcher is told which are still waiting, so the refusal at send time is no surprise.
     * Narrowed variants (a fulfilment mode, an inbound channel) are not offered, because a broadcast
     * has neither.
     */
    @Transactional(readOnly = true)
    public List<CampaignMessagePort.CourierTemplate> courierTemplates(UUID tenantId, UUID brandId) {
        Map<String, TemplateRow> byKey = new LinkedHashMap<>();
        for (TemplateRow template : templates.templatesForBrand(tenantId, brandId)) {
            boolean courierWording = NotificationClass.COURIER_DISPATCH.name().equals(template.notificationClass())
                    && NotificationChannel.SMS.name().equals(template.channel())
                    && "ACTIVE".equals(template.status())
                    && template.activeVersion() != null
                    && template.fulfillmentMode() == null
                    && template.channelSource() == null;
            if (!courierWording) {
                continue;
            }
            TemplateRow already = byKey.get(template.templateKey());
            if (already == null || (already.brandId() == null && template.brandId() != null)) {
                byKey.put(template.templateKey(), template);
            }
        }

        List<CampaignMessagePort.CourierTemplate> offered = new ArrayList<>();
        for (TemplateRow template : byKey.values()) {
            Map<String, String> bodies = new LinkedHashMap<>();
            java.util.TreeSet<String> variables = new java.util.TreeSet<>();
            boolean awaiting = false;
            for (VersionRow version :
                    templates.versions(tenantId, template.id(), Objects.requireNonNull(template.activeVersion()))) {
                bodies.put(version.locale(), version.bodyTemplate());
                variables.addAll(TemplateRenderer.variablesUsedIn(version.bodyTemplate()));
                awaiting |= TemplateProviderReviewService.withheld(version.providerReview());
            }
            offered.add(new CampaignMessagePort.CourierTemplate(
                    template.templateKey(), List.copyOf(variables), bodies, awaiting));
        }
        return offered;
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

        List<MessageLocale> chain = localeChain(tenantId, brandId, locale);

        Resolution last = Resolution.noTemplate();
        for (MessageLocale candidate : chain) {
            last = resolveExact(tenantId, brandId, templateKey, channel, candidate, fulfillmentMode, channelSource);
            if (last.isFound() || last.outcome() == Resolution.Outcome.NO_ACTIVE_TEMPLATE) {
                return last;
            }
        }
        return last;
    }

    /**
     * The wording this order message will use, brand then tenant then the platform's own (ADR 0020's
     * resolution order, whose last step ADR 0184 builds).
     *
     * <p>The platform default answers only when the tenant has <em>no active template at any scope</em>
     * for this key and channel. A tenant that wrote a template, and wrote it for the wrong set of
     * languages, hears about it through the suppression reason {@code NO_TEMPLATE_FOR_LOCALE}, as before;
     * quietly replacing its wording with the platform's would hide the gap it can fix. It answers only
     * for a customer-addressed message of exactly the class the platform wrote the wording for: a row of
     * the class {@code OPERATIONS_ALERT} shares these template keys (the same fact fans out to the
     * branch's operations chat), and a sentence written for the customer must never be sent to a group.
     *
     * @param notificationClass the class of the message being sent; null means «no platform default»,
     *        which is every caller that is not an order message
     */
    @Transactional(readOnly = true)
    public Resolution resolve(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationChannel channel,
            MessageLocale locale,
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable SalesChannelSystemType channelSource,
            @Nullable NotificationClass notificationClass) {

        Resolution own = resolve(tenantId, brandId, templateKey, channel, locale, fulfillmentMode, channelSource);
        if (own.outcome() != Resolution.Outcome.NO_ACTIVE_TEMPLATE || notificationClass == null) {
            return own;
        }
        return platformDefault(tenantId, brandId, templateKey, channel, locale, notificationClass)
                .orElse(own);
    }

    private Optional<Resolution> platformDefault(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationChannel channel,
            MessageLocale locale,
            NotificationClass notificationClass) {
        Optional<PlatformDefaultTemplates.Entry> entry = PlatformDefaultTemplates.forKey(templateKey);
        if (entry.isEmpty()
                || entry.get().notificationClass() != notificationClass
                || !PlatformDefaultTemplates.offeredOn(channel)) {
            return Optional.empty();
        }
        for (MessageLocale candidate : localeChain(tenantId, brandId, locale)) {
            Optional<String> body = entry.get().body(candidate.tag());
            if (body.isPresent()) {
                return Optional.of(Resolution.platformDefault(new PlatformWording(
                        entry.get(), candidate.tag(), PlatformDefaultTemplates.VERSION, body.get())));
            }
        }
        return Optional.empty();
    }

    /**
     * The languages to try, in order (ADR 0149, Decision 4): a customer whose language the brand does
     * not serve is written to in the brand's default, and where that wording is missing too, in the
     * registry's fallback. The brand-scoped "required" rule is checked when a version is activated; this
     * is its send-time partner, which is what lets a brand add a language after a template went live
     * without that template going silent for the customers who read it.
     */
    private List<MessageLocale> localeChain(UUID tenantId, UUID brandId, MessageLocale locale) {
        TenantLocaleSet served = brandLocales.brandLocaleSet(tenantId, brandId);
        List<String> chain = new ArrayList<>();
        chain.add(served.locales().contains(locale.tag()) ? locale.tag() : served.defaultLocale());
        chain.add(served.defaultLocale());
        chain.add(MessageLocale.FALLBACK.tag());
        return chain.stream()
                .distinct()
                .map(MessageLocale::parse)
                .flatMap(Optional::stream)
                .toList();
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

    /**
     * Whether the SMS gateway this tenant sends through refuses wordings it has not approved (ADR 0091).
     * The question behind the one-time-code card of Tab 1: the sentence is the platform's, and a gateway
     * that moderates must have approved it (ADR 0184, D19). It is also the question eligibility asks
     * before it sends the platform's order wording over SMS: those sentences are not template versions,
     * so nothing else says whether the gateway has approved them.
     */
    @Transactional(readOnly = true)
    public boolean smsGatewayModerates(UUID tenantId) {
        return templates.smsWordingAwaitsGateway(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TemplateRow> forBrand(UUID tenantId, UUID brandId) {
        return templates.templatesForBrand(tenantId, brandId);
    }

    /**
     * The templates that apply at this brand with what the list screen needs beside each: the
     * languages the version it is showing has, the languages it ought to have, and the worst
     * moderation state among them (ADR 0184, row 10.9a's Tab 1).
     *
     * <p>The version shown is the active one, or for a template with none the newest draft, so a
     * template nobody has activated still tells its author which language is missing. One read of the
     * version rows for the whole list, whatever its length.
     */
    @Transactional(readOnly = true)
    public List<Listing> listing(UUID tenantId, UUID brandId) {
        Map<UUID, List<ShownVersionRow>> shown = new LinkedHashMap<>();
        for (ShownVersionRow row : templates.shownVersions(tenantId, brandId)) {
            shown.computeIfAbsent(row.templateId(), id -> new ArrayList<>()).add(row);
        }
        // Two answers at most: this brand's set, and the union a tenant-wide template serves.
        List<String> brandRequired = null;
        List<String> tenantRequired = null;
        List<Listing> listing = new ArrayList<>();
        for (TemplateRow row : templates.templatesForBrand(tenantId, brandId)) {
            List<String> required;
            if (row.brandId() == null) {
                tenantRequired = tenantRequired != null
                        ? tenantRequired
                        : requiredLocales(tenantId, null).stream()
                                .map(MessageLocale::tag)
                                .toList();
                required = tenantRequired;
            } else {
                brandRequired = brandRequired != null
                        ? brandRequired
                        : requiredLocales(tenantId, row.brandId()).stream()
                                .map(MessageLocale::tag)
                                .toList();
                required = brandRequired;
            }
            List<ShownVersionRow> rows = shown.getOrDefault(row.id(), List.of());
            listing.add(new Listing(
                    row,
                    rows.isEmpty() ? null : rows.get(0).versionNumber(),
                    rows.stream().map(ShownVersionRow::locale).sorted().toList(),
                    required,
                    worstReview(rows)));
        }
        return listing;
    }

    private static String worstReview(List<ShownVersionRow> rows) {
        List<String> worstFirst = List.of("REJECTED", "PENDING", "APPROVED", "NOT_REQUIRED");
        return rows.stream()
                .map(ShownVersionRow::providerReview)
                .min(java.util.Comparator.comparingInt(review -> {
                    int rank = worstFirst.indexOf(review);
                    return rank < 0 ? worstFirst.size() : rank;
                }))
                .orElse("NOT_REQUIRED");
    }

    /**
     * One template as the list screen shows it.
     *
     * @param shownVersion the active version, or the newest draft when none is active; null when the
     *        template has no version at all
     * @param locales the languages {@code shownVersion} has a wording in
     * @param requiredLocales the languages this template's brand serves, which an activation needs
     * @param providerReview the worst ADR 0091 state among {@code shownVersion}'s languages
     */
    public record Listing(
            TemplateRow template,
            @Nullable Integer shownVersion,
            List<String> locales,
            List<String> requiredLocales,
            String providerReview) {}

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

    private String contentHashOf(MessageLocale locale, Wording wording, boolean showItemPrices) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("locale", locale.tag());
        parts.put("subject", wording.subject() == null ? "" : wording.subject());
        parts.put("body", wording.body());
        if (showItemPrices) {
            // Part of the proof only when on, so every version authored before the switch existed keeps
            // the hash it was frozen with.
            parts.put("showItemPrices", "true");
        }
        if (wording.coverAssetId() != null) {
            parts.put("cover", wording.coverAssetId().toString());
        }
        if (wording.targetPath() != null) {
            parts.put("path", wording.targetPath());
        }
        return ContentHashes.ofVariables(parts);
    }

    /**
     * One locale's text.
     *
     * @param subject null on a channel that has no subject, which SMS does not.
     *                Null rather than blank so "this channel has no subject" and
     *                "the author left it empty" stay distinguishable
     */
    public record Wording(
            @Nullable String subject,
            String body,
            @Nullable UUID coverAssetId,
            @Nullable String targetPath) {

        public Wording {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("A template version needs a body");
            }
        }

        /** A wording with no push fields: every channel but push. */
        public Wording(@Nullable String subject, String body) {
            this(subject, body, null, null);
        }
    }

    /**
     * Either the wording, or which of the two ways it was missing.
     *
     * <p>A found wording is one of two things: a tenant's template row and version, or the platform's
     * bundled default ({@link #platformDefault()}), never both. The two travel in different fields because
     * they are frozen differently: a template row by its id and version number, the platform's wording by
     * {@link PlatformDefaultTemplates#VERSION}.
     */
    public record Resolution(
            @Nullable TemplateRow template,
            @Nullable VersionRow version,
            @Nullable PlatformWording platformDefault,
            Outcome outcome) {

        public enum Outcome {
            FOUND,
            NO_ACTIVE_TEMPLATE,
            NO_TEMPLATE_FOR_LOCALE
        }

        static Resolution found(TemplateRow template, VersionRow version) {
            return new Resolution(template, version, null, Outcome.FOUND);
        }

        static Resolution platformDefault(PlatformWording wording) {
            return new Resolution(null, null, wording, Outcome.FOUND);
        }

        static Resolution noTemplate() {
            return new Resolution(null, null, null, Outcome.NO_ACTIVE_TEMPLATE);
        }

        static Resolution noLocale() {
            return new Resolution(null, null, null, Outcome.NO_TEMPLATE_FOR_LOCALE);
        }

        public boolean isFound() {
            return outcome == Outcome.FOUND;
        }

        /** Whether the wording is the platform's own, found below every tenant and brand template. */
        public boolean isPlatformDefault() {
            return platformDefault != null;
        }
    }

    /**
     * The platform's own sentence for one language, as it was when it was resolved.
     *
     * @param locale the language the sentence is actually in, which is the fallback's and not the
     *        customer's when the platform wrote nothing in the customer's
     * @param version {@link PlatformDefaultTemplates#VERSION}, the handle a notification freezes
     */
    public record PlatformWording(PlatformDefaultTemplates.Entry entry, String locale, int version, String body) {}

    /** A version does not exist in every locale HorecaOS sends in. */
    public static class IncompleteTranslationException extends RuntimeException {

        public IncompleteTranslationException(String message) {
            super(message);
        }
    }
}
