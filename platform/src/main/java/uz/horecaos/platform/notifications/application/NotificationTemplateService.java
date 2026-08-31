package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.TemplateRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.VersionRow;

/**
 * Authoring, approving, and resolving template wording (ADR 0020).
 *
 * <p>The rule this service exists to enforce is that a missing translation fails
 * while somebody is writing copy, not at 22:00 when a customer is waiting for a
 * confirmation. A version is a set of rows — one per locale — and
 * {@link #activate} refuses unless all three of ru, uz-Latn, and en are present.
 * The check cannot be a database constraint because it is a statement about a set
 * of rows and a CHECK sees one at a time.
 *
 * <p>The second rule is that a template can only name variables its schema
 * declares. That is checked when a draft is saved, so a typo is a refused draft
 * rather than a customer reading "Заказ {{orderNumbr}} принят".
 */
@Service
public class NotificationTemplateService {

    private static final TypeReference<Map<String, String>> SCHEMA_TYPE = new TypeReference<>() {};

    private final JdbcTemplateStore templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationTemplateService(JdbcTemplateStore templates, ObjectMapper objectMapper, Clock clock) {
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
     */
    @Transactional
    public UUID createTemplate(
            UUID tenantId,
            UUID brandId,
            String templateKey,
            NotificationClass notificationClass,
            NotificationChannel channel,
            String consentPurpose) {

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
                clock.instant());
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
            UUID tenantId, UUID templateId, Map<MessageLocale, Wording> wordings, Map<String, String> variablesSchema) {

        // Read for its side effect: a template id from another tenant must not be
        // given a version here, and the composite foreign key alone would let the
        // insert through on a matching id.
        var unused = templates
                .template(tenantId, templateId)
                .orElseThrow(
                        () -> new IllegalArgumentException("No template " + templateId + " belongs to this tenant"));

        List<MessageLocale> missing = MessageLocale.required().stream()
                .filter(locale -> !wordings.containsKey(locale))
                .toList();
        if (!missing.isEmpty()) {
            throw new IncompleteTranslationException(
                    "A version needs every locale before it can be saved; missing " + missing);
        }

        Set<String> declared = variablesSchema.keySet();
        int versionNumber = templates.nextVersionNumber(tenantId, templateId);
        Instant now = clock.instant();
        String schemaJson = objectMapper.writeValueAsString(variablesSchema);

        for (MessageLocale locale : MessageLocale.required()) {
            // Never null: the completeness check above already refused to reach
            // here unless every required locale is a key of this map.
            Wording wording = Objects.requireNonNull(wordings.get(locale));
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
                    now);
        }

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
    public void activate(UUID tenantId, UUID templateId, int versionNumber, String approvedBy) {
        TemplateRow template = templates
                .template(tenantId, templateId)
                .orElseThrow(
                        () -> new IllegalArgumentException("No template " + templateId + " belongs to this tenant"));

        List<VersionRow> versions = templates.versions(tenantId, templateId, versionNumber);
        List<MessageLocale> present =
                versions.stream().map(row -> MessageLocale.of(row.locale())).toList();
        List<MessageLocale> missing = MessageLocale.required().stream()
                .filter(locale -> !present.contains(locale))
                .toList();

        if (!missing.isEmpty()) {
            throw new IncompleteTranslationException(
                    "Version %d of this template cannot be activated: %s missing".formatted(versionNumber, missing));
        }

        int activated = templates.activateVersion(tenantId, templateId, versionNumber, approvedBy, clock.instant());
        if (activated != MessageLocale.required().size()) {
            // Some locale of this version was not a draft, which means another
            // operator activated or superseded it between the read above and this
            // update. Refusing is right: half of a version being live is worse
            // than the activation not happening.
            throw new IllegalStateException("Version %d was changed by someone else; %d of %d locales activated"
                    .formatted(
                            versionNumber, activated, MessageLocale.required().size()));
        }
        if (!templates.markTemplateActive(tenantId, templateId, versionNumber, template.version(), clock.instant())) {
            throw new IllegalStateException("The template was changed by someone else");
        }
    }

    // -------------------------------------------------------------- resolution

    /**
     * The wording this message will use, or the reason there is none.
     *
     * <p>Two lookups rather than one join, so the caller can tell "this tenant has
     * no confirmation template" from "it has one, but not in the language this
     * customer reads". Those are different problems for the tenant and a join
     * returns the same empty result for both.
     */
    @Transactional(readOnly = true)
    public Resolution resolve(
            UUID tenantId, UUID brandId, String templateKey, NotificationChannel channel, MessageLocale locale) {

        Optional<TemplateRow> template = templates.activeTemplate(tenantId, brandId, templateKey, channel.name());
        if (template.isEmpty() || template.get().activeVersion() == null) {
            return Resolution.noTemplate();
        }

        TemplateRow row = template.get();
        return templates
                .version(tenantId, row.id(), row.activeVersion(), locale.tag())
                .filter(version -> "ACTIVE".equals(version.status()))
                .map(version -> Resolution.found(row, version))
                .orElseGet(Resolution::noLocale);
    }

    /** The declared variable names of a stored version. */
    public Set<String> declaredVariables(VersionRow version) {
        return objectMapper
                .readValue(version.variablesSchemaJson(), SCHEMA_TYPE)
                .keySet();
    }

    @Transactional(readOnly = true)
    public List<TemplateRow> forBrand(UUID tenantId, UUID brandId) {
        return templates.templatesForBrand(tenantId, brandId);
    }

    @Transactional(readOnly = true)
    public List<VersionRow> versions(UUID tenantId, UUID templateId, int versionNumber) {
        return templates.versions(tenantId, templateId, versionNumber);
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
    public record Resolution(@Nullable TemplateRow template, @Nullable VersionRow version, Outcome outcome) {

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
