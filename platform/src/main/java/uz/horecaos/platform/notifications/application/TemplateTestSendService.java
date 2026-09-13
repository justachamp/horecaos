package uz.horecaos.platform.notifications.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
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
 * <p><strong>SMS only, for now.</strong> A Telegram test send would need a
 * bound chat rather than a free-form destination, and this service does not
 * yet resolve one — refused with a clear reason rather than attempted against
 * an invented recipient.
 *
 * <p>No row is written by this call, in {@code notifications.notifications}
 * or anywhere else: nothing here is a real message a tenant would ever need
 * to look back on, and every generated id below is a transient correlation
 * value for exactly one outbound call, the same status a lease token holds
 * (ADR 0076) rather than a row's own identity.
 */
@Service
public class TemplateTestSendService {

    private final JdbcTemplateStore templates;
    private final NotificationTransport transport;

    public TemplateTestSendService(JdbcTemplateStore templates, NotificationTransport transport) {
        this.templates = templates;
        this.transport = transport;
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
        if (channel != NotificationChannel.SMS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A test send is only available for an SMS template today; %s is not wired for one"
                            .formatted(channel));
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

        Map<String, String> sampleVariables = sampleValuesFor(version);
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
                "NotificationTemplateTestSend",
                templateId,
                template.templateKey());

        // The external call this whole method exists to make. Nothing above
        // this line opened a transaction, and nothing below writes one either
        // — see the class Javadoc for why.
        DispatchOutcome outcome = transport.dispatch(dispatch);
        return new TestSendOutcome(
                outcome.status().name(), outcome.providerStatus(), outcome.errorCode(), outcome.detail());
    }

    /**
     * Fills every declared, used variable with a sample value — the catalogue's
     * own description stands in where nothing better exists, so a rendered
     * test message reads as an example rather than throwing on a missing
     * value {@link TemplateRenderer#render} would otherwise refuse.
     */
    private Map<String, String> sampleValuesFor(VersionRow version) {
        Set<String> used = TemplateRenderer.variablesUsedIn(version.bodyTemplate());
        Map<String, String> sample = new LinkedHashMap<>();
        Map<String, String> catalogDefaults = defaultSamples();
        for (String name : used) {
            sample.put(name, catalogDefaults.getOrDefault(name, "[" + name + "]"));
        }
        return sample;
    }

    private Map<String, String> defaultSamples() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("orderNumber", "A-1042");
        defaults.put("amount", "85 000 UZS");
        defaults.put("currency", "UZS");
        defaults.put("reasonCode", "OUT_OF_STOCK");
        defaults.put("code", "482913");
        return defaults;
    }

    /** What came back from the one real send this call made. Never the destination it went to. */
    public record TestSendOutcome(
            String status,
            @Nullable String providerStatus,
            @Nullable String errorCode,
            @Nullable String detail) {}
}
