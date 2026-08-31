package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory;
import uz.horecaos.platform.customers.api.RecipientContactDirectory.ContactEndpoint;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.domain.ContentHashes;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.MoneyText;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.domain.SuppressionReason;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;

/**
 * Deciding whether a message may be sent, and freezing what it will say
 * (ADR 0020, step 2 and 3 of the command flow).
 *
 * <p>Consent is a gate rather than a filter. Every path out of here that ends in
 * "no" writes a {@code SUPPRESSED} row carrying the reason, because the question
 * a tenant actually asks is "why did the customer not get their confirmation?"
 * and a filtered-out message has no answer to it.
 *
 * <p>Consent is also read, never re-decided. ADR 0015 owns the append-only record
 * with its policy version and its evidence; this service asks
 * {@link ConsentDirectory} and does nothing with the answer but act on it. A
 * second module reasoning about consent would be a second answer to a legal
 * question that has one.
 *
 * <p>The class decides whether consent applies at all, and that distinction is
 * legal rather than tonal. An order confirmation is a receipt for money the
 * customer spent: it is {@code TRANSACTIONAL_REQUIRED}, it does not need marketing
 * consent, and gating it on one would withhold a receipt from someone who never
 * opted into promotions. A promotion is {@code MARKETING} and needs the current
 * decision at the applicable brand, purpose, and channel scope.
 *
 * <p>Everything resolved here is frozen onto the row. A tenant activating new
 * wording, or a customer changing their number, between this point and the send
 * must not change what was decided — the message that goes out is the one this
 * step described.
 */
@Service
public class NotificationEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEligibilityService.class);

    private static final TypeReference<Map<String, String>> VARIABLES_TYPE = new TypeReference<>() {};

    private final JdbcNotificationStore notifications;
    private final NotificationTemplateService templates;
    private final ConsentDirectory consent;
    private final RecipientContactDirectory contacts;
    private final OrderDirectory orders;
    private final NotificationTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final MessageLocale operationsGroupLocale;

    public NotificationEligibilityService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            ConsentDirectory consent,
            RecipientContactDirectory contacts,
            OrderDirectory orders,
            NotificationTransport transport,
            ObjectMapper objectMapper,
            Clock clock,
            // ADR 0058: "a group's language follows tenant configuration". No
            // tenant-level language column exists yet (see AGENTS.md's own
            // pattern for a product input this build states rather than
            // resolves, e.g. quiet hours). One platform-wide default until a
            // tenant configuration key exists to read instead.
            @Value("${horecaos.notifications.telegram.group-locale:ru}") String operationsGroupLocale) {
        this.notifications = notifications;
        this.templates = templates;
        this.consent = consent;
        this.contacts = contacts;
        this.orders = orders;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.operationsGroupLocale = MessageLocale.of(operationsGroupLocale);
    }

    /**
     * Runs the gate for one claimed message.
     *
     * @return true when the message became {@code READY}, false when it was
     *         suppressed. Either way the row is settled and the caller's claim is
     *         no longer held
     */
    @Transactional
    public boolean evaluate(NotificationRow row) {
        Instant now = clock.instant();
        NotificationChannel channel = NotificationChannel.valueOf(row.channel());
        NotificationClass notificationClass = NotificationClass.valueOf(row.notificationClass());
        // This service only ever runs on a row a worker just claimed, so the claim
        // token is always set; asserting it here rather than carrying the
        // @Nullable type through the method is what lets markReady below take a
        // plain UUID like every other claim-scoped write in this module does.
        UUID claimToken = Objects.requireNonNull(row.claimToken(), "eligibility runs only on a claimed row");

        if (!channel.isWired() || !transport.supports(channel.name())) {
            // Refused here rather than at the last step, so a tenant who authored
            // an email template is told the channel does not exist instead of
            // watching messages be created, resolved, rendered, and then fail.
            return suppress(row, SuppressionReason.CHANNEL_NOT_AVAILABLE, now);
        }

        Optional<OrderSummary> order = orders.summary(row.tenantId(), row.subjectId());
        if (order.isEmpty()) {
            // The order that caused this message is not visible to this tenant.
            // That is not a suppression — nothing was decided about a customer —
            // it is a data fault worth an operator's attention.
            throw new IllegalStateException(
                    "Notification %s names an order this tenant does not own".formatted(row.id()));
        }
        OrderSummary summary = order.get();

        // ADR 0020: "operations alerts target authorized groups... never a
        // customer. It has no consent to check because there is no data subject
        // in the ADR 0015 sense." The account/contact machinery below is entirely
        // about a customer's own contact; an operations-audience message has
        // neither and must not go near either directory.
        boolean operationsAudience = notificationClass == NotificationClass.OPERATIONS_ALERT;

        UUID accountId = null;
        MessageLocale locale;
        if (operationsAudience) {
            locale = operationsGroupLocale;
        } else {
            UUID resolvedAccount = summary.customerAccountId();
            if (resolvedAccount == null) {
                // A guest order has no ADR 0015 account, so there is no contact to
                // resolve and no consent record to read. The first slice takes
                // phone-authenticated customers, so this is the honest answer
                // rather than an error. Checking the id directly (rather than
                // hasAccount()) lets the compiler carry the non-null fact onward.
                return suppress(row, SuppressionReason.NO_RECIPIENT_ACCOUNT, now);
            }
            accountId = resolvedAccount;
            locale = contacts.preferredLocale(row.tenantId(), accountId)
                    .flatMap(MessageLocale::parse)
                    .orElse(MessageLocale.FALLBACK);
        }

        var resolution = templates.resolve(row.tenantId(), row.brandId(), row.templateKey(), channel, locale);
        var template = resolution.template();
        var version = resolution.version();
        if (template == null || version == null) {
            // Checking the resolved rows directly (rather than
            // resolution.isFound()) is what lets the compiler carry the non-null
            // fact into the rest of this method; Resolution guarantees the two
            // travel together.
            SuppressionReason reason =
                    switch (resolution.outcome()) {
                        case NO_ACTIVE_TEMPLATE -> SuppressionReason.NO_ACTIVE_TEMPLATE;
                        case NO_TEMPLATE_FOR_LOCALE -> SuppressionReason.NO_TEMPLATE_FOR_LOCALE;
                        case FOUND -> throw new IllegalStateException("unreachable");
                    };
            return suppress(row, reason, now);
        }

        // The gate. Only the classes that legally need a decision ask for one, and
        // absence of a decision is withheld rather than permitted — "we never
        // asked" and "they said yes" are the two states a default-true would merge.
        if (notificationClass.requiresConsent()) {
            String consentPurpose = Objects.requireNonNull(
                    template.consentPurpose(),
                    "template %s is %s and must declare a consent purpose"
                            .formatted(template.id(), notificationClass));
            boolean granted = consent.consentFor(row.tenantId(), accountId, row.brandId(), consentPurpose, channel.name())
                    .map(ConsentDirectory.ConsentState::granted)
                    .orElse(false);
            if (!granted) {
                return suppress(row, SuppressionReason.CONSENT_WITHHELD, now);
            }
        }

        if (notificationClass.respectsPreference()) {
            boolean disabled = notifications
                    .effectivePreference(
                            row.tenantId(), accountId, row.brandId(), notificationClass.name(), channel.name())
                    .map(preference -> !preference.enabled())
                    .orElse(false);
            if (disabled) {
                return suppress(row, SuppressionReason.PREFERENCE_DISABLED, now);
            }
        }

        UUID endpointId;
        if (operationsAudience) {
            // Already resolved when this intent was created: the fan-out trigger
            // (OperationsTelegramFanoutService) knows exactly which bound chat it
            // is writing for and sets recipient_endpoint_id at insert time, unlike
            // every customer-facing intent, whose endpoint is discovered here.
            endpointId = row.recipientEndpointId();
            if (endpointId == null) {
                throw new IllegalStateException(
                        "Operations alert %s was created without a resolved endpoint".formatted(row.id()));
            }
        } else {
            // Reachable only for a wired channel (the isWired() check above), and
            // every wired customer channel names a contact method; TELEGRAM's
            // operations audience never reaches this branch.
            var contactMethod = Objects.requireNonNull(
                    channel.contactMethod(), () -> channel + " is wired but names no contact method");
            UUID customerAccount = Objects.requireNonNull(
                    accountId, "customer-audience message reached endpoint resolution without an account");
            Optional<ContactEndpoint> contact =
                    contacts.primaryContact(row.tenantId(), customerAccount, contactMethod);
            if (contact.isEmpty()) {
                return suppress(row, SuppressionReason.NO_RECIPIENT_ENDPOINT, now);
            }
            endpointId = notifications.ensureEndpoint(
                    row.tenantId(),
                    customerAccount,
                    contact.get().method().name(),
                    contact.get().contactPointId(),
                    contact.get().normalizedHash(),
                    contact.get().verificationStatus(),
                    now);
        }

        Map<String, String> variables = variablesFor(row, summary);
        boolean ready = notifications.markReady(
                row.tenantId(),
                row.id(),
                claimToken,
                template.id(),
                version.versionNumber(),
                locale.tag(),
                accountId,
                endpointId,
                objectMapper.writeValueAsString(variables),
                ContentHashes.ofVariables(variables),
                now);

        if (!ready) {
            // The claim was lost, which means another worker settled this row.
            // Doing nothing is correct: the other worker's decision stands.
            log.debug("Notification {} was settled by another worker during eligibility", row.id());
        }
        return ready;
    }

    /**
     * The values the template may render.
     *
     * <p>Two sources, both already free of personal data. What the triggering event
     * carried is on the row — a rejection reason code lives on {@code OrderRejected}
     * and nowhere else — and what the order carries is read here. Nothing else may
     * write this map: the ADR 0032 classification test already forbids protected
     * values on an event payload, and the order summary is a deliberately narrow
     * port for the same reason.
     */
    private Map<String, String> variablesFor(NotificationRow row, OrderSummary order) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (row.variablesJson() != null && !row.variablesJson().isBlank()) {
            variables.putAll(objectMapper.readValue(row.variablesJson(), VARIABLES_TYPE));
        }
        variables.put("orderNumber", order.publicOrderNumber() == null ? "" : order.publicOrderNumber());
        variables.put("amount", MoneyText.format(order.totalMinor(), order.currency()));
        variables.put("currency", order.currency() == null ? "" : order.currency());
        return variables;
    }

    private boolean suppress(NotificationRow row, SuppressionReason reason, Instant now) {
        notifications.markSuppressed(row.tenantId(), row.id(), row.claimToken(), reason.name(), now);
        // Logged at info with the reason and the notification id, and deliberately
        // without the account, the endpoint, or the hash. A tenant reads this from
        // the Operations API; a log line is for the engineer watching the sweep.
        log.info("Notification {} suppressed: {}", row.id(), reason);
        return false;
    }
}

