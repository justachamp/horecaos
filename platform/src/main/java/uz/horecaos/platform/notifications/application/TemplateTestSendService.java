package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationVariableCatalog;
import uz.horecaos.platform.notifications.domain.TemplateRenderer;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.TemplateRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcTemplateStore.VersionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Sending one real message from a draft or active version, to an operator's
 * own test destination, before it ever reaches a customer (gap map rows
 * {@code 10.9a}/{@code X.27}).
 *
 * <p>An author authoring a version has had no way to see it delivered — only
 * to activate it and wait for a real customer to hit it. This bypasses ADR
 * 0020's consent/preference/quiet-hours gate on purpose (a test send is never
 * a customer message, so none of those questions apply) but keeps ADR 0091's
 * gate: a wording its SMS gateway has not approved is refused here exactly as
 * it would be refused at the real gateway, with the reason named instead of a
 * confusing provider error.
 *
 * <p><strong>SMS to a phone, Telegram to one of the brand's own bound chats.</strong> An SMS test goes
 * to the number the author types. A Telegram message has no number: it goes to a bound chat, and a
 * chat is an ADR 0026 binding, so the destination is a binding id and it must be one of the chats this
 * brand's routing screen lists and has not unbound (ADR 0184, row 10.9a). An author for one brand cannot
 * put a message into another brand's group by naming its binding. Any other channel is refused with its
 * name, because it has no adapter to send through.
 *
 * <p><strong>One audit fact per test send</strong> ({@code notification.template.test_sent}), which
 * settings.md 10.9 asks for: who sent a real message from which version, in which language, to which
 * channel, and what the route answered. The destination is not in it, and not stored anywhere: a phone
 * number in an audit row is a number in a table a support export reads. No row is written in
 * {@code notifications.notifications}, a Telegram channel does not track the message for an edit or a
 * skip ({@link NotificationDispatch#SUBJECT_TEMPLATE_TEST_SEND}: pressing the button twice posts twice),
 * and every generated id below is a transient correlation value
 * for exactly one outbound call, the same status a lease token holds (ADR 0076) rather than a row's
 * own identity.
 */
@Service
public class TemplateTestSendService {

    private final JdbcTemplateStore templates;
    private final NotificationTransport transport;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final OperationsSubscriptionDirectory chats;
    private final Clock clock;

    /** A service whose sends leave no audit fact and whose chats are never bound (tests, tools). */
    public TemplateTestSendService(JdbcTemplateStore templates, NotificationTransport transport) {
        this(
                templates,
                transport,
                fact -> {},
                () -> new AuthenticatedActor("template-test-send", java.util.Set.of(), Map.of()),
                new OperationsSubscriptionDirectory() {
                    @Override
                    public List<UUID> subscribedBindings(
                            UUID tenantId, UUID brandId, @Nullable UUID locationId, String eventClass) {
                        return List.of();
                    }

                    @Override
                    public List<ScopedBinding> tenantDigestBindings(UUID tenantId, String eventClass) {
                        return List.of();
                    }

                    @Override
                    public List<ScopedBinding> platformDigestBindings(String eventClass) {
                        return List.of();
                    }
                },
                Clock.systemUTC());
    }

    @Autowired
    public TemplateTestSendService(
            JdbcTemplateStore templates,
            NotificationTransport transport,
            AuditRecorder audit,
            CurrentActor currentActor,
            OperationsSubscriptionDirectory chats,
            Clock clock) {
        this.templates = templates;
        this.transport = transport;
        this.audit = audit;
        this.currentActor = currentActor;
        this.chats = chats;
        this.clock = clock;
    }

    /**
     * Renders and sends one locale of one version to {@code destination}.
     *
     * <p>Read-then-dispatch, deliberately not wrapped in one transaction: the
     * reads above are plain, single-statement {@code JdbcTemplateStore} calls,
     * and the dispatch below is the external call {@code
     * ExternalCallTransactionBoundaryTests} forbids inside one.
     *
     * @param destination the phone number to send to. Never stored — held only
     *                     for the length of this call, the same discipline
     *                     {@link NotificationDispatch} itself documents
     */
    public TestSendOutcome testSend(
            UUID tenantId, UUID brandId, UUID templateId, int versionNumber, String localeTag, String destination) {
        TemplateRow template = templates
                .template(tenantId, templateId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such notification template"));
        // The endpoint declares a BRAND-scoped capability, so the caller was
        // only ever authorised for the brand in the URL. Without this check,
        // NOTIFICATION_TEMPLATE_AUTHOR for one brand would be enough to
        // render a sibling brand's private wording into a real outbound SMS
        // and read it back in this response — a live side effect, not merely
        // a read. A tenant-wide default template is visible to every brand,
        // matching NotificationTemplateService's own precedence.
        if (template.brandId() != null && !template.brandId().equals(brandId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such notification template");
        }

        NotificationChannel channel = NotificationChannel.valueOf(template.channel());
        if (channel != NotificationChannel.SMS && channel != NotificationChannel.TELEGRAM) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A test send is available for an SMS or a Telegram template; %s has no adapter to send through"
                            .formatted(channel));
        }
        if (channel == NotificationChannel.TELEGRAM) {
            requireOwnChat(tenantId, brandId, destination);
        }

        MessageLocale locale = MessageLocale.parse(localeTag)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.VALIDATION_FAILED, "%s is not a supported locale".formatted(localeTag)));

        VersionRow version = templates
                .version(tenantId, templateId, versionNumber, locale.tag())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such template version"));

        if (TemplateProviderReviewService.withheld(version.providerReview())) {
            String reason = TemplateProviderReviewService.REJECTED.equals(version.providerReview())
                    ? "This wording was refused by the SMS gateway; a real send would fail the same way"
                    : "This wording is still awaiting the SMS gateway's approval; a real send would be refused";
            throw new ApiException(ErrorCode.VALIDATION_FAILED, reason);
        }

        Map<String, String> sampleVariables = sampleValuesFor(version, locale);
        String body;
        try {
            // version.bodyTemplate() is never null (body_template is NOT NULL),
            // and TemplateRenderer.render only ever answers null for a null
            // template — the @Nullable return exists for its other callers,
            // never reachable from this one.
            body = Objects.requireNonNull(
                    TemplateRenderer.render(version.bodyTemplate(), sampleVariables),
                    "a non-null template renders to a non-null body");
        } catch (TemplateRenderer.TemplateContractException missing) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, missing.getMessage());
        }

        UUID correlationId = UUID.randomUUID();
        NotificationDispatch dispatch = new NotificationDispatch(
                correlationId,
                correlationId,
                tenantId,
                brandId,
                null,
                channel.name(),
                destination,
                null,
                body,
                "test-send:" + correlationId,
                correlationId.toString(),
                NotificationDispatch.SUBJECT_TEMPLATE_TEST_SEND,
                templateId,
                template.templateKey());

        // The external call this whole method exists to make. Nothing above
        // this line opened a transaction, and nothing below writes one either
        // — see the class Javadoc for why.
        DispatchOutcome outcome = transport.dispatch(dispatch);
        recordTestSend(tenantId, brandId, template, versionNumber, locale, outcome);
        return new TestSendOutcome(
                outcome.status().name(), outcome.providerStatus(), outcome.errorCode(), outcome.detail());
    }

    /**
     * A Telegram test goes to a chat this brand has bound and not unbound, named by its binding id.
     * Refused with the same message for a malformed id, another brand's chat and a retired one: which of
     * the three it is would tell a caller about chats they may not see.
     */
    private void requireOwnChat(UUID tenantId, UUID brandId, String destination) {
        UUID bindingId;
        try {
            bindingId = UUID.fromString(destination.strip());
        } catch (IllegalArgumentException malformed) {
            throw notOneOfTheBrandsChats();
        }
        if (!chats.isActiveOperationsChat(tenantId, brandId, bindingId)) {
            throw notOneOfTheBrandsChats();
        }
    }

    private static ApiException notOneOfTheBrandsChats() {
        return new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "A Telegram test goes to one of this brand's bound chats; choose one from the routing tab");
    }

    private void recordTestSend(
            UUID tenantId,
            UUID brandId,
            TemplateRow template,
            int versionNumber,
            MessageLocale locale,
            DispatchOutcome outcome) {
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("templateKey", template.templateKey());
        sent.put("channel", template.channel());
        sent.put("versionNumber", versionNumber);
        sent.put("locale", locale.tag());
        sent.put("outcome", outcome.status().name());
        audit.record(AuditFact.of("notification.template.test_sent", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("notification.template", template.id())
                .targetVersion((long) template.version())
                .because("A test message was sent from a template version, to a destination the author named")
                .changed(ChangeDocuments.created(sent))
                .correlatedBy(template.id().toString())
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Fills every declared, used variable with a sample value — the catalogue's
     * own description stands in where nothing better exists, so a rendered
     * test message reads as an example rather than throwing on a missing
     * value {@link TemplateRenderer#render} would otherwise refuse.
     *
     * <p>The two personal variables read as invented people, never as anyone real: the sample is the
     * point of a test, and a test send must not reveal the customer or the courier of any order.
     */
    private Map<String, String> sampleValuesFor(VersionRow version, MessageLocale locale) {
        Set<String> used = TemplateRenderer.variablesUsedIn(version.bodyTemplate());
        Map<String, String> sample = new LinkedHashMap<>();
        Map<String, String> catalogDefaults = defaultSamples(version.showItemPrices());
        for (String name : used) {
            sample.put(name, catalogDefaults.getOrDefault(name, "[" + name + "]"));
        }
        return sample;
    }

    private static Map<String, String> defaultSamples(boolean showItemPrices) {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("orderNumber", "A-1042");
        defaults.put("amount", "85000");
        defaults.put("currency", "UZS");
        defaults.put("reasonCode", "OUT_OF_STOCK");
        defaults.put("code", "482913");
        defaults.put("restaurantName", "Oqtepa Lavash");
        defaults.put("deliveryTime", "19:45");
        defaults.put("preparationTime", "35");
        defaults.put(
                "productList", showItemPrices ? "2x Lavash (50000 UZS), 1x Cola (8000 UZS)" : "2x Lavash, 1x Cola");
        defaults.put("discountAmount", "5000");
        defaults.put("deliveryFee", "12000");
        defaults.put("customerName", "Aziz");
        defaults.put("courierFirstName", "Bobur");
        // A name the catalogue declines is never given a sample: leaving it out makes a wording that
        // got past authoring with one fail the test send with the variable named, which is the point.
        NotificationVariableCatalog.DECLINED.forEach(defaults::remove);
        // LOYALTY_POINTS_EXPIRING (ADR 0046, ADR 0181): the three typed values the loyalty adapter sends.
        defaults.put("loyaltyPoints", "12 000");
        defaults.put("loyaltyExpiresOn", "28.10.2026");
        defaults.put("loyaltyDaysRemaining", "14");
        return defaults;
    }

    /** What came back from the one real send this call made. Never the destination it went to. */
    public record TestSendOutcome(
            String status,
            @Nullable String providerStatus,
            @Nullable String errorCode,
            @Nullable String detail) {}
}
