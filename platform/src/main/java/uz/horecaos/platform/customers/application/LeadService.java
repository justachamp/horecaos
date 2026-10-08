package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.CustomerConfigurationKeys;
import uz.horecaos.platform.customers.api.LeadAssignedToLocation;
import uz.horecaos.platform.customers.api.LeadConversionTargets;
import uz.horecaos.platform.customers.api.LeadConverted;
import uz.horecaos.platform.customers.api.LeadIntake;
import uz.horecaos.platform.customers.api.LeadRegistered;
import uz.horecaos.platform.customers.api.LeadSource;
import uz.horecaos.platform.customers.api.LeadStatusChanged;
import uz.horecaos.platform.customers.domain.LeadClosedReason;
import uz.horecaos.platform.customers.domain.LeadStatus;
import uz.horecaos.platform.customers.domain.PhoneNumber;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Cursor;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Filter;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.LeadRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.NewLead;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore.Reach;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The call centre's leads (ADR 0111 §4-§7).
 *
 * <p>A lead is a guest who has phoned in, asked for a callback or enquired about catering and is not
 * yet an account with an order behind them. This service is the whole of what happens to one: it is
 * registered, handed to a branch, moved through a six-state machine and converted into the order or
 * the reservation that resulted, or declined with a reason. Each of those is an ADR 0027 audit fact
 * and an ADR 0004 outbox event written in the same transaction, and none of them contains a phone
 * number, a name or a note.
 *
 * <p><strong>Reach.</strong> Every read and every change takes the {@link Reach} of the caller's
 * grant, and a lead outside it is "no such lead" -- the same answer as one that does not exist, so
 * a lead id seen in a ticket is not an oracle. A branch sees the leads handed to it and no others,
 * and cannot hand one to another branch: assignment is the brand-level call centre's, because the
 * branch that is asked to take a lead is not the one deciding who else gets it.
 *
 * <p><strong>Personal data.</strong> The phone, the name and the notes are envelope-encrypted (ADR
 * 0029). A list and a detail show a masked number written once at creation and never decrypt;
 * {@link #reveal} is the one decrypt, purpose-stamped and audited, and its capability is the
 * existing {@code customer.pii.reveal}. The number is hashed under the same domain as a customer's
 * contact point so a lead can say "this number is also an account's" as a hint an operator confirms
 * through the existing merge endpoint -- never as an automatic match (ADR 0015).
 */
@Service
public class LeadService implements LeadIntake {

    /** Shares {@code CustomerProfileService.ContactType.PHONE}'s hash domain, so a lead matches an account's number. */
    static final String PHONE_LOOKUP_DOMAIN = "customer.contact.phone";

    private static final String LEAD_TABLE = "customer.leads";
    private static final Pattern PLAUSIBLE_PHONE = Pattern.compile("\\+\\d{9,15}");
    private static final Set<LeadStatus> OPEN =
            EnumSet.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.CALLBACK_SCHEDULED);

    private final JdbcLeadStore store;
    private final JdbcCustomerStore customers;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final ConfigurationResolver configuration;
    private final List<LeadConversionTargets> conversionTargets;
    private final Clock clock;

    public LeadService(
            JdbcLeadStore store,
            JdbcCustomerStore customers,
            FieldProtection protection,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            ConfigurationResolver configuration,
            List<LeadConversionTargets> conversionTargets,
            Clock clock) {
        this.store = store;
        this.customers = customers;
        this.protection = protection;
        this.audit = audit;
        this.events = events;
        this.configuration = configuration;
        this.conversionTargets = conversionTargets;
        this.clock = clock;
    }

    // ================================================================ registering

    /**
     * Registers a lead on behalf of an operator.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for an unusable phone, an unknown branch or a
     *                      source reserved for another path; {@code RESOURCE_NOT_FOUND} for an
     *                      account that is not this tenant's
     */
    @Transactional
    public LeadView register(NewLeadCommand command, ActorRef actor) {
        if (command.source() == LeadSource.CAMPAIGN_SCENARIO) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A campaign scenario's call task arrives through the lead intake, not by hand");
        }
        UUID leadId = insert(command, actor.subject(), actor);
        return view(requireLead(command.tenantId(), Reach.tenant(), leadId));
    }

    /** {@link LeadIntake}: another module hands the call centre a guest. Idempotent for a scenario step. */
    @Override
    @Transactional
    public UUID registerLead(LeadIntake.Registration registration) {
        NewLeadCommand command = new NewLeadCommand(
                registration.tenantId(),
                registration.brandId(),
                registration.source(),
                registration.phone(),
                registration.displayName(),
                registration.notes(),
                registration.customerAccountId(),
                null,
                registration.originCampaignId(),
                registration.originStepSequence());
        ActorRef actor = ActorRef.service(registration.actor());
        try {
            return insert(command, registration.actor(), actor);
        } catch (ApiException refused) {
            throw new IllegalArgumentException(refused.getMessage(), refused);
        }
    }

    private UUID insert(NewLeadCommand command, String createdBy, ActorRef actor) {
        UUID tenantId = command.tenantId();
        boolean scenario = command.source() == LeadSource.CAMPAIGN_SCENARIO;
        if (scenario != (command.originCampaignId() != null && command.originStepSequence() != null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only a campaign scenario carries a campaign and a step, and it always does");
        }
        String normalized = normalizePhone(command.phone());
        if (command.assignedLocationId() != null
                && !store.locationBelongsToBrand(tenantId, command.brandId(), command.assignedLocationId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No such branch in this brand");
        }
        if (command.customerAccountId() != null
                && customers.account(tenantId, command.customerAccountId()).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such customer");
        }

        UUID leadId = Ids.newId();
        String hash = protection.lookupHash(tenantId, PHONE_LOOKUP_DOMAIN, normalized);
        NewLead lead = new NewLead(
                leadId,
                tenantId,
                command.brandId(),
                command.source().name(),
                hash,
                protect(tenantId, leadId, "phone_encrypted", normalized),
                PhoneMasks.mask(normalized),
                optionalProtect(tenantId, leadId, "display_name_encrypted", command.displayName()),
                optionalProtect(tenantId, leadId, "notes_encrypted", command.notes()),
                command.customerAccountId(),
                command.assignedLocationId(),
                command.originCampaignId(),
                command.originStepSequence(),
                createdBy);
        Instant now = clock.instant();

        if (scenario) {
            if (!store.insertScenarioOnce(lead, now)) {
                // The same step for the same number already produced a lead: the inbox delivered twice.
                return store.findScenarioLead(
                                tenantId,
                                Objects.requireNonNull(command.originCampaignId()),
                                Objects.requireNonNull(command.originStepSequence()),
                                hash)
                        .orElseThrow();
            }
        } else {
            store.insert(lead, now);
        }

        events.publishEvent(new LeadRegistered(
                Ids.newId(),
                tenantId,
                leadId,
                command.brandId(),
                command.source().name(),
                now));
        if (command.assignedLocationId() != null) {
            events.publishEvent(
                    new LeadAssignedToLocation(Ids.newId(), tenantId, leadId, command.assignedLocationId(), now));
        }
        audit.record(managed(AuditFact.of("customer.lead.registered", AuditClass.BUSINESS), actor)
                .by(actor)
                .at(ResourceScope.brand(tenantId, command.brandId()))
                .target("customer_lead", leadId)
                .because("Lead registered from " + command.source().name())
                .changed(ChangeDocuments.created(Map.of(
                        "status", LeadStatus.NEW.name(),
                        "source", command.source().name())))
                .correlatedBy(leadId.toString())
                .occurredAt(now)
                .build());
        return leadId;
    }

    // ===================================================================== reading

    /** One lead, or empty when it is not this reach's. */
    @Transactional(readOnly = true)
    public Optional<LeadView> find(UUID tenantId, Reach reach, UUID leadId) {
        return store.find(tenantId, leadId).filter(reach::admits).map(row -> detail(row));
    }

    /** The queue, newest first. {@code attention} narrows it to what somebody owes a call. */
    @Transactional(readOnly = true)
    public LeadPage list(UUID tenantId, Reach reach, Query query, @Nullable Cursor cursor, int limit) {
        Instant now = clock.instant();
        Duration window = query.attention() ? Duration.ofMinutes(reminderMinutes(tenantId)) : null;
        Filter filter = new Filter(
                query.statuses().stream().map(LeadStatus::name).toList(),
                query.source() == null ? null : query.source().name(),
                query.assignedLocationId(),
                query.unassigned(),
                query.customerAccountId(),
                window);
        List<LeadRow> rows = store.list(tenantId, reach, filter, cursor, limit, now);
        List<LeadView> items = rows.stream().map(LeadService::view).toList();
        Cursor next = rows.size() < limit
                ? null
                : new Cursor(rows.getLast().createdAt(), rows.getLast().id());
        return new LeadPage(items, next);
    }

    /** The leads linked to one account, for its card. */
    @Transactional(readOnly = true)
    public List<LeadView> forAccount(UUID tenantId, UUID accountId, int limit) {
        return store.forAccount(tenantId, accountId, limit).stream()
                .map(LeadService::view)
                .toList();
    }

    private int reminderMinutes(UUID tenantId) {
        Integer value = configuration
                .resolve(CustomerConfigurationKeys.LEAD_CALLBACK_REMINDER_MINUTES, ResourceScope.tenant(tenantId))
                .value();
        return value == null || value < 0 ? 60 : value;
    }

    // =================================================================== transitions

    /**
     * Moves a lead along its machine.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for a lead outside the reach; {@code
     *                      UNPROCESSABLE_STATE} for an edge the machine does not have;
     *                      {@code VALIDATION_FAILED} for a conversion with no order or reservation
     *                      to point at, or a reason a decline needs; {@code STALE_VERSION}
     */
    @Transactional
    public LeadView transition(
            UUID tenantId, Reach reach, UUID leadId, Transition command, int expectedVersion, ActorRef actor) {
        LeadRow lead = requireLead(tenantId, reach, leadId);
        requireVersion(lead, expectedVersion);
        LeadStatus from = LeadStatus.valueOf(lead.status());
        LeadStatus to = command.target();
        if (!from.canMoveTo(to)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A lead that is %s cannot become %s".formatted(from, to),
                    Map.of("from", from.name(), "to", to.name()));
        }
        Instant now = clock.instant();

        LeadRow moved =
                switch (to) {
                    case CONTACTED -> lead.withOutcome(to.name(), null, null, null, null);
                    case CALLBACK_SCHEDULED -> {
                        Instant due = command.callbackDueAt();
                        if (due == null || !due.isAfter(now.minus(Duration.ofMinutes(5)))) {
                            throw new ApiException(
                                    ErrorCode.VALIDATION_FAILED, "A callback needs a time that has not already passed");
                        }
                        yield lead.withOutcome(to.name(), due, null, null, null);
                    }
                    case CONVERTED -> {
                        UUID orderId = command.convertedOrderId();
                        UUID reservationId = command.convertedReservationId();
                        if ((orderId == null) == (reservationId == null)) {
                            throw new ApiException(
                                    ErrorCode.VALIDATION_FAILED,
                                    "A lead converts into exactly one of an order and a reservation");
                        }
                        requireConversionTarget(tenantId, lead.brandId(), orderId, reservationId);
                        yield lead.withOutcome(to.name(), null, orderId, reservationId, null);
                    }
                    case DECLINED, LOST -> {
                        LeadClosedReason reason = command.closedReason();
                        if (reason == null) {
                            throw new ApiException(
                                    ErrorCode.VALIDATION_FAILED, "A lead that is declined or lost needs a reason");
                        }
                        yield lead.withOutcome(to.name(), null, null, null, reason.name());
                    }
                    case NEW -> throw new IllegalStateException("The machine has no edge into NEW");
                };

        persist(moved, expectedVersion, now);
        if (from != to) {
            events.publishEvent(new LeadStatusChanged(Ids.newId(), tenantId, leadId, from.name(), to.name(), now));
        }
        if (to == LeadStatus.CONVERTED) {
            events.publishEvent(new LeadConverted(
                    Ids.newId(), tenantId, leadId, moved.convertedOrderId(), moved.convertedReservationId(), now));
        }
        audit.record(managed(AuditFact.of("customer.lead.status_changed", AuditClass.BUSINESS), actor)
                .by(actor)
                .at(ResourceScope.brand(tenantId, lead.brandId()))
                .target("customer_lead", leadId)
                .targetVersion((long) expectedVersion + 1)
                .because(
                        command.reason() == null || command.reason().isBlank()
                                ? (from == to ? "Callback rescheduled" : "Lead moved from %s to %s".formatted(from, to))
                                : command.reason())
                .changed(ChangeDocuments.diff(statusFacts(lead), statusFacts(moved)))
                .correlatedBy(leadId.toString())
                .occurredAt(now)
                .build());
        return view(requireLead(tenantId, Reach.tenant(), leadId));
    }

    /** The facts a status change diffs: codes and an instant, never a person. */
    private static Map<String, Object> statusFacts(LeadRow lead) {
        java.util.LinkedHashMap<String, Object> facts = new java.util.LinkedHashMap<>();
        facts.put("status", lead.status());
        facts.put(
                "callbackDueAt",
                lead.callbackDueAt() == null ? null : lead.callbackDueAt().toString());
        facts.put("closedReason", lead.closedReason());
        return facts;
    }

    /** Records the capability a staff actor used; a system actor holds none. */
    private static AuditFact.Builder managed(AuditFact.Builder fact, ActorRef actor) {
        return actor.type() == ActorRef.Type.USER ? fact.usingCapability(Capability.CUSTOMER_LEAD_MANAGE.code()) : fact;
    }

    private void requireConversionTarget(
            UUID tenantId, UUID brandId, @Nullable UUID orderId, @Nullable UUID reservationId) {
        boolean real = conversionTargets.stream()
                .anyMatch(targets -> orderId != null
                        ? targets.orderExists(tenantId, brandId, orderId)
                        : targets.reservationExists(tenantId, brandId, Objects.requireNonNull(reservationId)));
        if (!real) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    orderId != null ? "No such order in this brand" : "No such reservation in this brand");
        }
    }

    // ======================================================================= hand-off

    /**
     * Hands a lead to a branch of its brand (ADR 0111 §5, §6): a field, not a workflow. Allowed again
     * while the lead is open -- a lead given to a branch with one busy operator is reassigned by
     * hand, because there is no load-aware router.
     */
    @Transactional
    public LeadView assign(
            UUID tenantId, Reach reach, UUID leadId, UUID locationId, int expectedVersion, ActorRef actor) {
        LeadRow lead = requireLead(tenantId, reach, leadId);
        requireVersion(lead, expectedVersion);
        LeadStatus status = LeadStatus.valueOf(lead.status());
        if (!OPEN.contains(status)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A lead that is %s is no longer anyone's to hand over".formatted(status));
        }
        if (!store.locationBelongsToBrand(tenantId, lead.brandId(), locationId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "No such branch in this brand");
        }
        if (locationId.equals(lead.assignedLocationId())) {
            return view(lead);
        }
        Instant now = clock.instant();
        LeadRow handed = lead.withHandOff(locationId, now);
        persist(handed, expectedVersion, now);
        events.publishEvent(new LeadAssignedToLocation(Ids.newId(), tenantId, leadId, locationId, now));
        java.util.HashMap<String, Object> before = new java.util.HashMap<>();
        before.put(
                "assignedLocationId",
                lead.assignedLocationId() == null
                        ? null
                        : lead.assignedLocationId().toString());
        audit.record(managed(AuditFact.of("customer.lead.assigned", AuditClass.BUSINESS), actor)
                .by(actor)
                .at(ResourceScope.brand(tenantId, lead.brandId()))
                .target("customer_lead", leadId)
                .targetVersion((long) expectedVersion + 1)
                .because("Lead handed to a branch")
                .changed(ChangeDocuments.diff(before, Map.of("assignedLocationId", locationId.toString())))
                .correlatedBy(leadId.toString())
                .occurredAt(now)
                .build());
        return view(requireLead(tenantId, Reach.tenant(), leadId));
    }

    // ======================================================================== reveal

    /**
     * The guest's number, name and notes, decrypted: the one decrypt a lead has (ADR 0029). One
     * audit fact for the call, written before anything is decrypted and in the same transaction, the
     * way every customer reveal is.
     */
    @Transactional
    public RevealedLeadContact reveal(UUID tenantId, Reach reach, UUID leadId, String purpose, ActorRef actor) {
        LeadRow lead = requireLead(tenantId, reach, leadId);
        int revealed = 1 + (lead.displayNameEncrypted() == null ? 0 : 1) + (lead.notesEncrypted() == null ? 0 : 1);
        AuditFact.Builder fact = AuditFact.of("customer.lead.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.brand(tenantId, lead.brandId()))
                .target("customer_lead", leadId)
                .because(purpose)
                .changed(ChangeDocuments.created(Map.of("revealedCount", revealed)))
                .correlatedBy(leadId.toString())
                .occurredAt(clock.instant());
        if (actor.type() == ActorRef.Type.USER) {
            fact.usingCapability(Capability.CUSTOMER_PII_REVEAL.code());
        }
        audit.record(fact.build());
        return new RevealedLeadContact(
                protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(lead.phoneEncrypted()),
                        new RecordRef(LEAD_TABLE, "phone_encrypted", leadId),
                        purpose),
                lead.displayNameEncrypted() == null
                        ? null
                        : protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(lead.displayNameEncrypted()),
                                new RecordRef(LEAD_TABLE, "display_name_encrypted", leadId),
                                purpose),
                lead.notesEncrypted() == null
                        ? null
                        : protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(lead.notesEncrypted()),
                                new RecordRef(LEAD_TABLE, "notes_encrypted", leadId),
                                purpose));
    }

    // ======================================================================== helpers

    private LeadRow requireLead(UUID tenantId, Reach reach, UUID leadId) {
        return store.find(tenantId, leadId)
                .filter(reach::admits)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such lead"));
    }

    private static void requireVersion(LeadRow lead, int expectedVersion) {
        if (lead.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, lead.version());
        }
    }

    private void persist(LeadRow changed, int expectedVersion, Instant now) {
        if (!store.save(changed, expectedVersion, now)) {
            // Lost a race between the read and the write: report it as the stale version it is.
            int current = store.find(changed.tenantId(), changed.id())
                    .map(LeadRow::version)
                    .orElse(expectedVersion);
            throw ApiException.staleVersion(expectedVersion, current);
        }
    }

    private String protect(UUID tenantId, UUID leadId, String column, String plaintext) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(LEAD_TABLE, column, leadId), plaintext)
                .serialize();
    }

    private @Nullable String optionalProtect(UUID tenantId, UUID leadId, String column, @Nullable String plaintext) {
        return plaintext == null || plaintext.isBlank() ? null : protect(tenantId, leadId, column, plaintext.strip());
    }

    /** The number as it is hashed and encrypted; the message never contains the value. */
    static String normalizePhone(String raw) {
        String normalized;
        try {
            normalized = PhoneNumber.normalize(raw);
        } catch (IllegalArgumentException blank) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A phone number is required");
        }
        if (!PLAUSIBLE_PHONE.matcher(normalized).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That is not a usable phone number");
        }
        return normalized;
    }

    private LeadView detail(LeadRow row) {
        List<UUID> accounts = customers.accountsWithContact(row.tenantId(), "PHONE", row.phoneLookupHash());
        List<UUID> otherLeads = store.openLeadsWithPhone(row.tenantId(), row.phoneLookupHash(), row.id());
        return view(row)
                .withHints(
                        accounts.stream()
                                .filter(id -> !id.equals(row.customerAccountId()))
                                .toList(),
                        otherLeads);
    }

    static LeadView view(LeadRow row) {
        return new LeadView(
                row.id(),
                row.brandId(),
                LeadStatus.valueOf(row.status()),
                LeadSource.valueOf(row.source()),
                row.phoneMasked(),
                row.displayNameEncrypted() != null,
                row.notesEncrypted() != null,
                row.customerAccountId(),
                row.assignedLocationId(),
                row.assignedAt(),
                row.callbackDueAt(),
                row.originCampaignId(),
                row.originStepSequence(),
                row.convertedOrderId(),
                row.convertedReservationId(),
                row.closedReason(),
                row.createdBy(),
                row.version(),
                row.createdAt(),
                row.updatedAt(),
                List.of(),
                List.of());
    }

    // ======================================================================== shapes

    /**
     * @param assignedLocationId a hand-off at creation, when the operator already knows the branch
     */
    public record NewLeadCommand(
            UUID tenantId,
            UUID brandId,
            LeadSource source,
            String phone,
            @Nullable String displayName,
            @Nullable String notes,
            @Nullable UUID customerAccountId,
            @Nullable UUID assignedLocationId,
            @Nullable UUID originCampaignId,
            @Nullable Integer originStepSequence) {}

    /** What an operator asks of the machine. Only the fields its target uses are read. */
    public record Transition(
            LeadStatus target,
            @Nullable Instant callbackDueAt,
            @Nullable UUID convertedOrderId,
            @Nullable UUID convertedReservationId,
            @Nullable LeadClosedReason closedReason,
            @Nullable String reason) {}

    public record Query(
            Set<LeadStatus> statuses,
            @Nullable LeadSource source,
            @Nullable UUID assignedLocationId,
            boolean unassigned,
            @Nullable UUID customerAccountId,
            boolean attention) {

        public static Query everything() {
            return new Query(Set.of(), null, null, false, null, false);
        }
    }

    public record LeadPage(List<LeadView> items, @Nullable Cursor next) {}

    /** The decrypted contact, from {@link #reveal}. */
    public record RevealedLeadContact(
            String phone,
            @Nullable String displayName,
            @Nullable String notes) {}

    /**
     * A lead as a list or a detail shows it: never a number, a name or a note, only whether there are
     * a name and notes to reveal.
     *
     * @param possibleAccountIds accounts holding the same number -- a hint to confirm, never a link
     * @param otherOpenLeadIds   other open leads holding it
     */
    public record LeadView(
            UUID id,
            UUID brandId,
            LeadStatus status,
            LeadSource source,
            String phoneMasked,
            boolean hasName,
            boolean hasNotes,
            @Nullable UUID customerAccountId,
            @Nullable UUID assignedLocationId,
            @Nullable Instant assignedAt,
            @Nullable Instant callbackDueAt,
            @Nullable UUID originCampaignId,
            @Nullable Integer originStepSequence,
            @Nullable UUID convertedOrderId,
            @Nullable UUID convertedReservationId,
            @Nullable String closedReason,
            String createdBy,
            int version,
            Instant createdAt,
            Instant updatedAt,
            List<UUID> possibleAccountIds,
            List<UUID> otherOpenLeadIds) {

        LeadView withHints(List<UUID> accounts, List<UUID> leads) {
            return new LeadView(
                    id,
                    brandId,
                    status,
                    source,
                    phoneMasked,
                    hasName,
                    hasNotes,
                    customerAccountId,
                    assignedLocationId,
                    assignedAt,
                    callbackDueAt,
                    originCampaignId,
                    originStepSequence,
                    convertedOrderId,
                    convertedReservationId,
                    closedReason,
                    createdBy,
                    version,
                    createdAt,
                    updatedAt,
                    accounts,
                    leads);
        }
    }
}
