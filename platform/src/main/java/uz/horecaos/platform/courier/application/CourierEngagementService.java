package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.EngagementStatus;
import uz.horecaos.platform.courier.domain.RegistrationWarningState;
import uz.horecaos.platform.courier.domain.VerificationMethod;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Registering a courier, capturing their self-employment registration, and
 * verifying it (ADR 0042).
 *
 * <p>The registration identifier never exists in this class as anything but a
 * parameter on its way into {@link FieldProtection} or a return value on its way
 * out of a declared reveal. Its validity dates do, in clear, and that asymmetry
 * is the point: "which couriers expire this month" is the only reason to hold
 * any of this, an encrypted date cannot answer it, and a date beside a courier
 * row is a much smaller fact than the number it belongs to.
 *
 * <p>A courier reaches {@code ACTIVE} only through {@link #verify}. There is no
 * method that activates an engagement without recording who attested to what and
 * when, because an unverified registration produces no error anywhere — it is
 * discovered by an inspector, and by then every delivery since the lapse is on
 * the record.
 */
@Service
public class CourierEngagementService {

    /**
     * One answer for every rejected evidence reference.
     *
     * <p>"Not yours" and "does not exist" are the same sentence on purpose. The
     * alternative turns this endpoint into a platform-wide existence oracle for
     * media asset ids: submit a uuid, read which of the two answers came back,
     * and learn whether some other tenant holds that asset. The caller who owns
     * the asset never sees this message, so it costs them nothing.
     */
    private static final String NO_SUCH_EVIDENCE = "The evidence media asset is not available in this tenant";

    private final JdbcCourierStore couriers;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final CourierPolicyResolver policies;
    private final MediaAvailability media;
    private final Clock clock;

    public CourierEngagementService(
            JdbcCourierStore couriers,
            FieldProtection protection,
            AuditRecorder audit,
            CourierPolicyResolver policies,
            MediaAvailability media,
            Clock clock) {
        this.couriers = couriers;
        this.protection = protection;
        this.audit = audit;
        this.policies = policies;
        this.media = media;
        this.clock = clock;
    }

    /**
     * Registers the person and opens their engagement in
     * {@code PENDING_VERIFICATION}. Deliberately two steps: onboarding somebody
     * and attesting to their registration are different acts by different people
     * on different days, and a single call would let the first imply the second.
     */
    @Transactional
    public Registration register(NewCourier command) {
        UUID courierId = UUID.randomUUID();
        String protectedName = protection
                .protect(
                        command.tenantId(),
                        DataClass.PERSONAL,
                        new FieldProtection.RecordRef("fulfillment.couriers", "protected_full_name", courierId),
                        command.fullName())
                .serialize();

        couriers.insertCourier(new CourierRow(
                courierId,
                command.tenantId(),
                command.courierTypeId(),
                command.principalSubject(),
                command.displayReference(),
                protectedName,
                "ACTIVE",
                1));

        UUID engagementId = UUID.randomUUID();
        couriers.insertEngagement(new EngagementRow(
                engagementId,
                command.tenantId(),
                courierId,
                EngagementStatus.PENDING_VERIFICATION,
                command.engagedFrom(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                RegistrationWarningState.VALID,
                null,
                1));

        audit.record(AuditFact.of("courier.engagement.opened", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.tenant(command.tenantId()))
                .target("courier_engagement", engagementId)
                .because(command.reason())
                .changed(Map.of(
                        "courierReference",
                        command.displayReference(),
                        "engagementType",
                        "SELF_EMPLOYED",
                        "status",
                        EngagementStatus.PENDING_VERIFICATION.name()))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(command.correlationId())
                .occurredAt(clock.instant())
                .build());

        return new Registration(courierId, engagementId);
    }

    /**
     * Records that somebody sighted the evidence, and activates the engagement.
     *
     * <p>{@code reverificationDueOn} is the earlier of the attested validity date
     * and today plus the policy's re-verification days, because an attestation is
     * evidence about a past instant rather than a standing fact. A registration
     * valid for three more years still decays as evidence after six months.
     */
    @Transactional
    public EngagementRow verify(VerifyRegistration command) {
        EngagementRow engagement = couriers.findEngagement(command.tenantId(), command.engagementId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No such engagement: " + command.engagementId()));

        if (command.method() != VerificationMethod.MANUAL_ATTESTATION) {
            // REGISTRY_LOOKUP is modelled and not built. Accepting it here would
            // record an attestation nobody made, under a method nobody ran.
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Only MANUAL_ATTESTATION is implemented; whether an authoritative "
                            + "machine-readable registration source exists is an open input on ADR 0042");
        }
        if (!command.validUntil().isAfter(today())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A registration that has already expired cannot be attested as valid");
        }
        requireOwnEvidence(command.tenantId(), command.evidenceMediaId());

        CourierCompensationPolicy policy = policies.resolve(ResourceScope.tenant(command.tenantId()));
        LocalDate decayDue = today().plusDays(policy.reverificationDays());
        LocalDate dueOn = decayDue.isBefore(command.validUntil()) ? decayDue : command.validUntil();

        String protectedRef = protection
                .protect(
                        command.tenantId(),
                        DataClass.PERSONAL_SENSITIVE,
                        new FieldProtection.RecordRef(
                                "fulfillment.courier_engagements",
                                "protected_registration_ref",
                                command.engagementId()),
                        command.registrationIdentifier())
                .serialize();

        boolean applied = couriers.verify(
                command.tenantId(),
                command.engagementId(),
                engagement.version(),
                protectedRef,
                command.validUntil(),
                dueOn,
                command.method(),
                command.actor().subject(),
                command.evidenceMediaId(),
                warningStateFor(dueOn, policy, today()),
                clock.instant());

        if (!applied) {
            throw ApiException.staleVersion(engagement.version(), engagement.version() + 1L);
        }

        // The change document carries dates and a media reference and never the
        // identifier: an audit trail is read by more people than the record is.
        audit.record(AuditFact.of("courier.registration.verified", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.tenant(command.tenantId()))
                .target("courier_engagement", command.engagementId())
                .because(command.reason())
                .changed(Map.of(
                        "method",
                        command.method().name(),
                        "registrationValidUntil",
                        command.validUntil().toString(),
                        "reverificationDueOn",
                        dueOn.toString()))
                .evidence(
                        command.evidenceMediaId() == null
                                ? null
                                : command.evidenceMediaId().toString())
                .usingCapability("courier.registration.verify")
                .correlatedBy(command.correlationId())
                .occurredAt(clock.instant())
                .build());

        return couriers.findEngagement(command.tenantId(), command.engagementId())
                .orElseThrow();
    }

    /** A manager suspending an engagement for an operational reason. */
    @Transactional
    public void suspend(
            UUID tenantId, UUID engagementId, String reasonCode, ActorRef actor, String reason, String correlationId) {

        boolean applied = couriers.suspend(
                tenantId,
                engagementId,
                EngagementStatus.SUSPENDED_OPERATIONAL,
                reasonCode,
                RegistrationWarningState.VALID,
                clock.instant());
        if (!applied) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The engagement is not in a state that can be suspended");
        }

        audit.record(AuditFact.of("courier.engagement.suspended", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_engagement", engagementId)
                .because(reason)
                .changed(Map.of("status", EngagementStatus.SUSPENDED_OPERATIONAL.name(), "reasonCode", reasonCode))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Reveals the registration identifier for the accountant export, under a
     * declared purpose that ADR 0029 records as an audit fact. The stored
     * statement holds only a reference; this is the only path that resolves it.
     */
    @Transactional
    public String revealRegistrationIdentifier(
            UUID tenantId, UUID engagementId, String purpose, ActorRef actor, String correlationId) {

        String stored = couriers.readProtectedRegistrationRef(tenantId, engagementId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No registration is recorded on this engagement"));

        String revealed = protection.reveal(
                tenantId,
                uz.horecaos.platform.iam.api.protection.ProtectedValue.deserialize(stored),
                new FieldProtection.RecordRef(
                        "fulfillment.courier_engagements", "protected_registration_ref", engagementId),
                purpose);

        audit.record(AuditFact.of("courier.registration.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_engagement", engagementId)
                .because(purpose)
                .usingCapability("courier.registration.reveal")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());

        return revealed;
    }

    /** The engagement dispatch reads. Absent means this courier has none. */
    public Optional<EngagementRow> liveEngagement(UUID tenantId, UUID courierId) {
        return couriers.findLiveEngagement(tenantId, courierId);
    }

    /**
     * Refuses an evidence reference that is not this tenant's own asset.
     *
     * <p>The id arrives in the request body, and until this check existed nothing
     * between the controller and the {@code UPDATE} looked at it: the tenant
     * predicate was applied to the engagement row and never to the asset. What it
     * points at is the scan of a courier's self-employment registration
     * certificate — a named person's tax document, ADR 0029 personal data, held
     * with PRIVATE visibility — so a tenant storing another tenant's id keeps a
     * durable pointer into somebody else's private evidence, and copies that
     * pointer into its own audit trail on the way past.
     *
     * <p>V0069 puts the same rule in the database, and the two are not
     * redundant. The constraint is the backstop for a path that does not come
     * through here; this is what makes the refusal a 400 with a code a client can
     * branch on rather than a 500 carrying a constraint name.
     *
     * <p>{@link MediaAvailability#allDisplayable} is the only media question
     * another module may ask, and it is the right one: it answers "exists,
     * belongs to this tenant, and is verified" as a single boolean, so no part of
     * the reason leaks back out. Verified matters here as much as it does for a
     * menu photograph — an attestation citing an upload that was never checked is
     * evidence of nothing.
     *
     * <p>A null id is accepted. Evidence is optional under ADR 0042: a manual
     * attestation is a person's sworn sighting, and they may have sighted paper.
     */
    private void requireOwnEvidence(UUID tenantId, @Nullable UUID evidenceMediaId) {
        if (evidenceMediaId == null) {
            return;
        }
        if (!media.allDisplayable(tenantId, Set.of(new MediaAssetId(evidenceMediaId)))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, NO_SUCH_EVIDENCE);
        }
    }

    static RegistrationWarningState warningStateFor(
            LocalDate dueOn, CourierCompensationPolicy policy, LocalDate today) {

        if (dueOn.isBefore(today)) {
            return RegistrationWarningState.LAPSED;
        }
        return dueOn.isAfter(today.plusDays(policy.warningDays()))
                ? RegistrationWarningState.VALID
                : RegistrationWarningState.EXPIRING;
    }

    private LocalDate today() {
        // The tenant's own day boundary belongs to ADR 0043's business-day
        // policy; until a courier engagement has a location on it, UTC is the
        // honest approximation and is stated rather than hidden.
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * A courier engagement to create.
     *
     * @param displayReference a non-personal handle a dispatch board may show
     */
    public record NewCourier(
            UUID tenantId,
            UUID courierTypeId,
            String principalSubject,
            String displayReference,
            String fullName,
            LocalDate engagedFrom,
            ActorRef actor,
            String reason,
            String correlationId) {}

    /**
     * A registration to verify.
     *
     * @param evidenceMediaId optional; checked for ownership only when present
     */
    public record VerifyRegistration(
            UUID tenantId,
            UUID engagementId,
            String registrationIdentifier,
            LocalDate validUntil,
            VerificationMethod method,
            @Nullable UUID evidenceMediaId,
            ActorRef actor,
            String reason,
            String correlationId) {}

    public record Registration(UUID courierId, UUID engagementId) {}
}
