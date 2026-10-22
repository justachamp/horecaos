package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCorrectionStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCorrectionStore.CorrectionRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AddressRow;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * A data subject's right to have what is wrong put right, against evidence (ADR 0029: "correct: amend with evidence,
 * preserving prior values under retention").
 *
 * <p>The edits an operator could already make (a name, a date of birth, a contact point, an address) overwrite in place and
 * keep neither the value they replaced nor why. A correction is the same edit, made through the same {@link
 * CustomerProfileService} method (so the version check, the normalisation and the verification reset are not
 * re-implemented here), with two things added in the same transaction: the value as it was, protected under the envelope and
 * bound to its own history row, and a reference to the evidence that made the change right. The history is what a
 * data-subject export hands back ({@code CustomerRecordsExportParticipant}) and what an erasure removes
 * ({@code CustomerRecordsErasureParticipant}); nobody reads the prior value in any other way.
 *
 * <p>The evidence is a <em>reference</em>, never the evidence: a ticket, a document number, a case id. The pattern refuses
 * spaces, so that a name or a sentence typed into the box is a validation error and not a second place the person is spelled
 * out; the database enforces the same pattern.
 *
 * <p>A correction that changes nothing (the same name, the same number spelled differently) records nothing and says so.
 */
@Service
public class CustomerCorrectionService {

    /** What may be corrected. */
    public enum Subject {
        DISPLAY_NAME,
        DATE_OF_BIRTH,
        CONTACT_POINT,
        ADDRESS
    }

    /** What a correction did. */
    public sealed interface Outcome {

        /** The value changed; the prior value was kept under this id. */
        record Applied(UUID correctionId, int version) implements Outcome {}

        /** The value was already what the caller asked for; nothing was written. */
        record Unchanged() implements Outcome {}
    }

    /** The same pattern V0684 enforces on the column. */
    public static final Pattern EVIDENCE_REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,79}$");

    private static final String CORRECTION_TABLE = "customer.profile_corrections";
    private static final String ACCOUNT_TABLE = "customer.customer_accounts";
    private static final String CONTACT_TABLE = "customer.contact_points";

    private final CustomerProfileService profiles;
    private final JdbcCustomerStore store;
    private final JdbcCorrectionStore corrections;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final Clock clock;

    public CustomerCorrectionService(
            CustomerProfileService profiles,
            JdbcCustomerStore store,
            JdbcCorrectionStore corrections,
            FieldProtection protection,
            ObjectMapper objectMapper,
            AuditRecorder audit,
            Clock clock) {
        this.profiles = profiles;
        this.store = store;
        this.corrections = corrections;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Corrects the display name.
     *
     * @throws CustomerProfileService.StaleRecordException when the account moved on from {@code expectedVersion}
     */
    @Transactional
    public Outcome correctDisplayName(
            UUID tenantId,
            UUID accountId,
            int expectedVersion,
            @Nullable String displayName,
            String evidenceReference,
            ActorRef actor) {
        requireEvidence(evidenceReference);
        AccountRow account =
                store.account(tenantId, accountId).orElseThrow(CustomerProfileService.AccountNotFoundException::new);
        String next = blankToNull(displayName);
        if (Objects.equals(next, account.displayName())) {
            return new Outcome.Unchanged();
        }
        int version = profiles.updateProfile(
                tenantId, accountId, expectedVersion, next, account.preferredLocale(), account.preferredTimezone());
        Map<String, @Nullable Object> prior = new LinkedHashMap<>();
        prior.put("displayName", account.displayName());
        return applied(tenantId, accountId, Subject.DISPLAY_NAME, null, evidenceReference, prior, version, actor);
    }

    /**
     * Corrects the date of birth to a date. Clearing one is a different act and not a correction.
     *
     * @throws IllegalArgumentException when {@code dateOfBirth} is not an ISO date
     */
    @Transactional
    public Outcome correctDateOfBirth(
            UUID tenantId,
            UUID accountId,
            int expectedVersion,
            String dateOfBirth,
            String evidenceReference,
            ActorRef actor) {
        requireEvidence(evidenceReference);
        try {
            LocalDate.parse(dateOfBirth);
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("dateOfBirth must be an ISO-8601 date (yyyy-MM-dd)");
        }
        AccountRow account =
                store.account(tenantId, accountId).orElseThrow(CustomerProfileService.AccountNotFoundException::new);
        String before = account.dateOfBirthEncrypted() == null
                ? null
                : protection.reveal(
                        tenantId,
                        ProtectedValue.deserialize(account.dateOfBirthEncrypted()),
                        new RecordRef(ACCOUNT_TABLE, "date_of_birth_encrypted", accountId),
                        "Correction: preserve the value being replaced");
        if (dateOfBirth.equals(before)) {
            return new Outcome.Unchanged();
        }
        int version = profiles.updateDateOfBirth(tenantId, accountId, expectedVersion, dateOfBirth);
        Map<String, @Nullable Object> prior = new LinkedHashMap<>();
        prior.put("dateOfBirth", before);
        return applied(tenantId, accountId, Subject.DATE_OF_BIRTH, null, evidenceReference, prior, version, actor);
    }

    /**
     * Corrects a contact point's value. The type is the row's own, and verification resets, as {@link
     * CustomerProfileService#updateContactPoint} documents.
     *
     * @throws CustomerProfileService.ContactPointNotFoundException when this is not the account's contact point
     */
    @Transactional
    public Outcome correctContactPoint(
            UUID tenantId,
            UUID accountId,
            UUID contactPointId,
            String value,
            String evidenceReference,
            ActorRef actor) {
        requireEvidence(evidenceReference);
        ContactPointRow before = store.contactPoints(tenantId, accountId).stream()
                .filter(contact -> contact.id().equals(contactPointId))
                .findFirst()
                .orElseThrow(CustomerProfileService.ContactPointNotFoundException::new);
        String priorValue = protection.reveal(
                tenantId,
                ProtectedValue.deserialize(before.encryptedValue()),
                new RecordRef(CONTACT_TABLE, "encrypted_value", contactPointId),
                "Correction: preserve the value being replaced");
        profiles.updateContactPoint(tenantId, accountId, contactPointId, value);
        ContactPointRow after = store.contactPoint(tenantId, contactPointId).orElseThrow();
        if (after.normalizedHash().equals(before.normalizedHash())) {
            return new Outcome.Unchanged();
        }
        Map<String, @Nullable Object> prior = new LinkedHashMap<>();
        prior.put("type", before.type());
        prior.put("value", priorValue);
        return applied(tenantId, accountId, Subject.CONTACT_POINT, contactPointId, evidenceReference, prior, 0, actor);
    }

    /**
     * Corrects one of the account's addresses: the whole address, as the edit it wraps replaces it.
     *
     * @throws CustomerProfileService.AddressNotFoundException when this is not the account's active address
     * @throws CustomerProfileService.StaleRecordException      when the address moved on from {@code expectedVersion}
     */
    @Transactional
    public Outcome correctAddress(
            UUID tenantId,
            UUID accountId,
            UUID addressId,
            int expectedVersion,
            String label,
            AddressFields fields,
            @Nullable String deliveryInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource,
            String evidenceReference,
            ActorRef actor) {
        requireEvidence(evidenceReference);
        AddressRow before = store.address(tenantId, accountId, addressId)
                .orElseThrow(CustomerProfileService.AddressNotFoundException::new);
        CustomerProfileService.RevealedAddress current =
                profiles.reveal(tenantId, before, "Correction: preserve the value being replaced");
        boolean same = current.label().equals(label)
                && current.fields().equals(fields)
                && Objects.equals(current.deliveryInstructions(), deliveryInstructions)
                && Objects.equals(current.latitude(), latitude)
                && Objects.equals(current.longitude(), longitude)
                && current.coordinateSource() == coordinateSource;
        if (same) {
            return new Outcome.Unchanged();
        }
        int version = profiles.updateAddress(
                tenantId,
                accountId,
                addressId,
                expectedVersion,
                label,
                fields,
                deliveryInstructions,
                latitude,
                longitude,
                coordinateSource);
        Map<String, @Nullable Object> prior = new LinkedHashMap<>();
        prior.put("label", current.label());
        prior.put("fields", current.fields());
        prior.put("deliveryInstructions", current.deliveryInstructions());
        prior.put("latitude", current.latitude());
        prior.put("longitude", current.longitude());
        prior.put("coordinateSource", current.coordinateSource().name());
        return applied(tenantId, accountId, Subject.ADDRESS, addressId, evidenceReference, prior, version, actor);
    }

    /**
     * One account's corrections, newest first, without the values they replaced.
     *
     * @throws CustomerErasureService.AccountNotFoundException when the account is not this tenant's
     */
    @Transactional(readOnly = true)
    public List<CorrectionRow> history(UUID tenantId, UUID accountId) {
        if (!store.accountExists(tenantId, accountId)) {
            throw new CustomerErasureService.AccountNotFoundException();
        }
        return corrections.history(tenantId, accountId);
    }

    private Outcome applied(
            UUID tenantId,
            UUID accountId,
            Subject subject,
            @Nullable UUID targetId,
            String evidenceReference,
            Map<String, @Nullable Object> prior,
            int version,
            ActorRef actor) {
        UUID correctionId = Ids.newId();
        Instant now = clock.instant();
        String protectedPrior = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef(CORRECTION_TABLE, "prior_value_protected", correctionId),
                        objectMapper.writeValueAsString(prior))
                .serialize();
        corrections.insert(
                correctionId,
                tenantId,
                accountId,
                subject.name(),
                targetId,
                evidenceReference,
                protectedPrior,
                actor.type().name(),
                actor.subject(),
                now);
        audit.record(AuditFact.of("customer.correction.applied", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target(ACCOUNT_TABLE, accountId)
                .because("Corrected against evidence " + evidenceReference)
                // Staff 9.3a: a correction is recorded once, as a new fact. What it replaced is protected on the
                // history
                // row, and neither the old nor the new value belongs on the audit trail.
                .changed(ChangeDocuments.created(Map.of(
                        "correctionId", correctionId.toString(),
                        "subject", subject.name(),
                        "evidenceReference", evidenceReference)))
                .correlatedBy(accountId.toString())
                .occurredAt(now)
                .build());
        return new Outcome.Applied(correctionId, version);
    }

    private static void requireEvidence(String evidenceReference) {
        if (!EVIDENCE_REFERENCE.matcher(evidenceReference).matches()) {
            throw new IllegalArgumentException(
                    "evidenceReference must be a reference to the evidence (3 to 80 characters "
                            + "of letters, digits and . _ : / # -, no spaces), not the evidence itself");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Exposed for the controller's validation message. */
    public static Optional<String> evidenceProblem(String evidenceReference) {
        return EVIDENCE_REFERENCE.matcher(evidenceReference).matches()
                ? Optional.empty()
                : Optional.of(
                        "evidenceReference must be a reference (letters, digits and . _ : / # -, 3 to 80 characters, no spaces)");
    }
}
