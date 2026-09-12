package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.domain.ComplianceField;
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
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
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
        return register(command, ComplianceFile.empty());
    }

    /**
     * Registers the person and records whatever of the compliance file the
     * operator already has (IA 3.3).
     *
     * <p>One transaction, because the alternative — register, then a second call
     * to file the documents — leaves a window in which a courier exists with a
     * name and nothing else, and the window is exactly as long as the operator
     * takes to be interrupted. A partial file is still accepted: an incomplete
     * file is a state the roster is built to show, and refusing the registration
     * would make the honest answer "type something in the passport box".
     */
    @Transactional
    public Registration register(NewCourier command, ComplianceFile file) {
        requireOwnPhoto(command.tenantId(), file.photoMediaId());

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

        writeComplianceFile(command.tenantId(), courierId, file, command.actor().subject());

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
                        EngagementStatus.PENDING_VERIFICATION.name(),
                        // Which documents were filed, never what any of them said.
                        // A field name is a fact about the form; its contents are
                        // the thing ADR 0029 keeps out of an audit trail, which is
                        // read by more people than the record is.
                        "complianceFieldsRecorded",
                        fieldNames(file.recorded().keySet())))
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
                ProtectedValue.deserialize(stored),
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

    // ------------------------------------------------------- the compliance file

    /**
     * Records or corrects the compliance file of a courier who is already on the
     * roster (IA 3.3).
     *
     * <p>Writes what the call carries and clears what it names; a field it
     * mentions in neither is left exactly as it was. That asymmetry is forced by
     * ADR 0029 rather than chosen: nothing outside a reveal holds the plaintext
     * of these columns, so a detail pane correcting a plate cannot re-send the
     * passport it never read, and a whole-row replace would erase it.
     */
    @Transactional
    public void recordComplianceFile(
            UUID tenantId, UUID courierId, ComplianceFile file, ActorRef actor, String reason, String correlationId) {

        requireCourier(tenantId, courierId);
        requireOwnPhoto(tenantId, file.photoMediaId());

        if (file.recorded().isEmpty()
                && file.cleared().isEmpty()
                && file.vehicleFuelType() == null
                && file.photoMediaId() == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The request records no field, clears none, and changes nothing");
        }

        writeComplianceFile(tenantId, courierId, file, actor.subject());

        audit.record(AuditFact.of("courier.compliance.recorded", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .changed(Map.of(
                        // Field names only, on both sides. What a passport number
                        // is remains unknown to the audit trail, and "which
                        // documents did this manager touch" stays answerable.
                        "complianceFieldsRecorded",
                        fieldNames(file.recorded().keySet()),
                        "complianceFieldsCleared",
                        fieldNames(file.cleared())))
                .usingCapability("courier.engagement.manage")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * Decrypts the compliance file under a declared purpose (ADR 0029, IA 3.3).
     *
     * <p>The one path out of these nine columns, and the only reason the
     * {@code courier.pii.reveal} capability exists. Absent fields are absent from
     * the answer rather than present and empty: "no licence is on file" and "the
     * licence field is blank" are different statements and only one of them is
     * true.
     *
     * <p>One audit fact for the whole file rather than one per field. The fact
     * ADR 0027 wants recorded is that somebody opened this courier's documents
     * for this stated reason; nine facts differing only in a field name would
     * make that harder to read, not easier, and would still not say which the
     * reader's eye actually landed on.
     */
    @Transactional
    public Map<ComplianceField, String> revealComplianceFile(
            UUID tenantId, UUID courierId, String purpose, ActorRef actor, String correlationId) {

        requireCourier(tenantId, courierId);

        Map<ComplianceField, String> stored = couriers.readComplianceFile(tenantId, courierId);
        Map<ComplianceField, String> revealed = new EnumMap<>(ComplianceField.class);
        stored.forEach((field, ciphertext) -> revealed.put(
                field,
                protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(ciphertext),
                        new FieldProtection.RecordRef("fulfillment.couriers", field.column(), courierId),
                        purpose)));

        audit.record(AuditFact.of("courier.compliance.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(purpose)
                .changed(Map.of("complianceFieldsRevealed", fieldNames(revealed.keySet())))
                .usingCapability("courier.pii.reveal")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());

        return revealed;
    }

    /**
     * Protects each supplied value under its own field's class and hands the
     * ciphertexts to the store.
     *
     * <p>Every value is bound by AAD to this row <em>and this column</em>, so a
     * ciphertext moved from the passport column to the notes column fails to
     * decrypt rather than quietly revealing the wrong document.
     */
    private void writeComplianceFile(UUID tenantId, UUID courierId, ComplianceFile file, String actorSubject) {
        Map<ComplianceField, String> ciphertexts = new EnumMap<>(ComplianceField.class);
        file.recorded()
                .forEach((field, plaintext) -> ciphertexts.put(
                        field,
                        protection
                                .protect(
                                        tenantId,
                                        field.dataClass(),
                                        new FieldProtection.RecordRef(
                                                "fulfillment.couriers", field.column(), courierId),
                                        plaintext)
                                .serialize()));

        couriers.recordComplianceFile(
                tenantId,
                courierId,
                ciphertexts,
                file.cleared(),
                file.vehicleFuelType(),
                file.photoMediaId(),
                actorSubject,
                clock.instant());
    }

    /**
     * Refuses a photograph that is not this tenant's own verified asset.
     *
     * <p>The same rule {@link #requireOwnEvidence} states for an attestation
     * scan, and the same answer for both halves of the refusal, for the reason
     * {@link #NO_SUCH_EVIDENCE} gives: distinguishing "not yours" from "does not
     * exist" would make this endpoint an existence oracle for asset ids. The
     * message differs only because the operator is looking at a photograph
     * field, and an error naming evidence would send them to the wrong form.
     */
    private void requireOwnPhoto(UUID tenantId, @Nullable UUID photoMediaId) {
        if (photoMediaId == null) {
            return;
        }
        if (!media.allDisplayable(tenantId, Set.of(new MediaAssetId(photoMediaId)))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "The photograph is not an available media asset in this tenant");
        }
    }

    /** The tenant predicate, stated once, before anything reads or writes this row. */
    private void requireCourier(UUID tenantId, UUID courierId) {
        if (couriers.findCourier(tenantId, courierId).isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier: " + courierId);
        }
    }

    /** Sorted so two runs of the same change produce the same audit line. */
    private static String fieldNames(Set<ComplianceField> fields) {
        return fields.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
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

    /**
     * The compliance file as a command carries it (IA 3.3).
     *
     * <p>Three-valued on purpose. A field in {@code recorded} is written; a field
     * in {@code cleared} is removed; a field in neither is untouched. "Untouched"
     * has to be expressible because the caller cannot see what it is not
     * changing — see {@link #recordComplianceFile} for why that is a property of
     * ADR 0029 rather than of this API.
     *
     * @param recorded plaintext to protect, per field
     * @param cleared fields to remove, stated rather than implied by absence
     * @param vehicleFuelType {@code PETROL|DIESEL|GAS|ELECTRIC|HYBRID|NONE}; null
     *     leaves it alone. Held in clear: an attribute of a vehicle, and the
     *     planning question it answers is an aggregate
     * @param photoMediaId a media asset this tenant owns; null leaves it alone.
     *     There is no clear-the-photo here: the image is governed by media's own
     *     retention and visibility, and detaching an asset is that module's act
     */
    public record ComplianceFile(
            Map<ComplianceField, String> recorded,
            Set<ComplianceField> cleared,
            @Nullable String vehicleFuelType,
            @Nullable UUID photoMediaId) {

        public ComplianceFile {
            recorded = Map.copyOf(recorded);
            cleared = Set.copyOf(cleared);
        }

        public static ComplianceFile empty() {
            return new ComplianceFile(Map.of(), Set.of(), null, null);
        }
    }
}
