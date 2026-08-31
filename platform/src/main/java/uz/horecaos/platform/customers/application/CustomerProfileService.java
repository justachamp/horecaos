package uz.horecaos.platform.customers.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.customers.domain.PhoneNumber;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * Contact points and addresses (ADR 0015, ADR 0029).
 *
 * <p>Every personal value is encrypted with the row bound into the associated
 * data, so a ciphertext copied to another row or tenant fails to decrypt rather
 * than quietly revealing the wrong person's phone number. Lookup uses a separate
 * keyed hash: deterministic encryption over a domain as small as Uzbek mobile
 * numbers would let anyone with read access confirm whether a given number is a
 * customer.
 */
@Service
public class CustomerProfileService {

    private static final String CONTACT_TABLE = "customer.contact_points";
    private static final String ADDRESS_TABLE = "customer.addresses";

    private final JdbcCustomerStore store;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CustomerProfileService(
            JdbcCustomerStore store, FieldProtection protection, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.protection = protection;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Stores a phone or email.
     *
     * <p>Adding a contact that another account already holds is allowed and
     * deliberate. Two people share a household phone and a recycled number
     * changes owner, so refusing — or worse, merging — would be wrong.
     */
    @Transactional
    public UUID addContactPoint(UUID tenantId, UUID accountId, ContactType type, String rawValue, boolean primary) {

        requireAccount(tenantId, accountId);
        String normalized = normalize(type, rawValue);
        UUID contactId = UUID.randomUUID();

        ProtectedValue encrypted = protection.protect(
                tenantId, DataClass.PERSONAL, new RecordRef(CONTACT_TABLE, "encrypted_value", contactId), normalized);

        store.insertContactPoint(
                contactId,
                tenantId,
                accountId,
                type.name(),
                protection.lookupHash(tenantId, type.lookupDomain(), normalized),
                encrypted.serialize(),
                primary,
                clock.instant());

        return contactId;
    }

    /**
     * Accounts holding this contact value.
     *
     * <p>Returns every match. A single-result signature would invite treating a
     * phone number as an identity key, which is the auto-merge ADR 0015 forbids;
     * a support agent seeing two results is exactly the intended outcome.
     */
    @Transactional(readOnly = true)
    public List<UUID> findAccountsByContact(UUID tenantId, ContactType type, String rawValue) {
        return store.accountsWithContact(
                tenantId, type.name(), protection.lookupHash(tenantId, type.lookupDomain(), normalize(type, rawValue)));
    }

    /**
     * Reveals a contact value.
     *
     * @param purpose recorded as an audit fact. The difference between an agent
     *                viewing one customer and exporting fifty thousand is exactly
     *                what this parameter exists to capture
     */
    @Transactional(readOnly = true)
    public List<RevealedContact> revealContactPoints(UUID tenantId, UUID accountId, String purpose) {
        return store.contactPoints(tenantId, accountId).stream()
                .map(row -> new RevealedContact(
                        row.id(),
                        ContactType.valueOf(row.type()),
                        protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(row.encryptedValue()),
                                new RecordRef(CONTACT_TABLE, "encrypted_value", row.id()),
                                purpose),
                        row.verificationStatus(),
                        row.isPrimary()))
                .toList();
    }

    /**
     * Which contact points an account holds, without revealing any of them.
     *
     * <p>A separate method rather than a projection of {@link #revealContactPoints},
     * because the projection would still have decrypted. A settings screen needs to
     * know that a verified phone exists and which contact is primary; deriving that
     * from a list of decrypted values would put a purpose row in the audit log, and
     * a plaintext number in a heap, for data the screen then throws away. Nothing
     * here touches {@code encrypted_value}.
     */
    @Transactional(readOnly = true)
    public List<ContactPointSummary> contactPointSummaries(UUID tenantId, UUID accountId) {
        return store.contactPoints(tenantId, accountId).stream()
                .map(row -> new ContactPointSummary(
                        row.id(), ContactType.valueOf(row.type()), row.verificationStatus(), row.isPrimary()))
                .toList();
    }

    /**
     * Stores a delivery address.
     *
     * <p>The address lines are encrypted as one document rather than per column:
     * no query needs a street name, and splitting them would leak structure
     * without buying anything. That document carries подъезд, этаж and ориентир
     * as structured fields, because those are what actually locate a door here
     * and a courier cannot use them if they are buried in a street line.
     *
     * <p>Coordinates stay in clear. A delivery cannot be routed without them, and
     * a coordinate identifies a building rather than a person.
     *
     * <p>The caller must say why the point is present or absent. Inferring the
     * source from whether coordinates arrived would collapse "not geocoded yet"
     * and "this address has no point" back into the same state, which is the
     * distinction this parameter exists to keep.
     */
    @Transactional
    public UUID addAddress(
            UUID tenantId,
            UUID accountId,
            String label,
            AddressFields fields,
            @Nullable String deliveryInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource) {

        requireAccount(tenantId, accountId);
        requireCoordinatesMatchSource(coordinateSource, latitude, longitude);
        UUID addressId = UUID.randomUUID();

        String document = objectMapper.writeValueAsString(fields);
        ProtectedValue encryptedFields = protection.protect(
                tenantId, DataClass.PERSONAL, new RecordRef(ADDRESS_TABLE, "encrypted_fields", addressId), document);

        String encryptedInstructions = deliveryInstructions == null
                ? null
                : protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef(ADDRESS_TABLE, "delivery_instructions_encrypted", addressId),
                                deliveryInstructions)
                        .serialize();

        store.insertAddress(
                addressId,
                tenantId,
                accountId,
                label,
                encryptedFields.serialize(),
                encryptedInstructions,
                latitude,
                longitude,
                coordinateSource.name(),
                clock.instant());

        return addressId;
    }

    /**
     * Refuses a source that disagrees with the coordinates.
     *
     * <p>The database enforces this too. The check exists for the message: a
     * constraint violation names {@code ck_address_coordinate_source_agrees} and
     * tells an operator nothing, while a claimed GEOCODER result with no point
     * is a specific mistake with a specific fix.
     */
    private static void requireCoordinatesMatchSource(
            CoordinateSource source, @Nullable Double latitude, @Nullable Double longitude) {

        if (source == null) {
            throw new IllegalArgumentException("A coordinate source is required");
        }
        if (source == CoordinateSource.LEGACY_UNSOURCED) {
            // Only a migration may claim an unknown origin. Allowing it here
            // would let new rows opt out of recording where their point came
            // from, and the column would stop meaning anything.
            throw new IllegalArgumentException("LEGACY_UNSOURCED describes rows written before the source was recorded "
                    + "and cannot be chosen for a new address");
        }
        boolean hasPoint = latitude != null && longitude != null;
        if (latitude != null ^ longitude != null) {
            // Half a coordinate points at the equator or the prime meridian.
            throw new IllegalArgumentException("A latitude without a longitude, or the reverse, is not a location");
        }
        if (source.requiresPoint() && !hasPoint) {
            throw new IllegalArgumentException("Coordinate source " + source + " claims a point but none was supplied");
        }
        if (!source.requiresPoint() && hasPoint) {
            throw new IllegalArgumentException(
                    "Coordinate source " + source + " means there is no point, but one was supplied");
        }
    }

    @Transactional(readOnly = true)
    public List<RevealedAddress> revealAddresses(UUID tenantId, UUID accountId, String purpose) {
        return store.addresses(tenantId, accountId).stream()
                .map(row -> reveal(tenantId, row, purpose))
                .toList();
    }

    /**
     * One address of one account, decrypted.
     *
     * <p>The account is a predicate of the query rather than a check on the way
     * out, so an id belonging to somebody else is empty before anything is
     * decrypted — the reveal is not attempted at all, and therefore nothing is
     * recorded against a purpose for a row the caller had no business reading.
     */
    @Transactional(readOnly = true)
    public Optional<RevealedAddress> revealAddress(UUID tenantId, UUID accountId, UUID addressId, String purpose) {
        return store.address(tenantId, accountId, addressId).map(row -> reveal(tenantId, row, purpose));
    }

    /**
     * Replaces the content of one of an account's own addresses.
     *
     * <p>The whole address, never a field of it. The lines live inside one
     * encrypted document, so a partial write would have to decrypt, merge and
     * re-encrypt — three chances to keep a stale field — and the coordinate and its
     * source have to move together anyway: V0021's constraint is an equivalence,
     * and a customer who drops a new pin on a landmark-only address is changing
     * both halves of it in one act.
     *
     * @return the stored version after the write
     * @throws AddressNotFoundException when the address is not this account's
     *                                  active address, which is the same answer
     *                                  as "no such address"
     * @throws StaleRecordException    when it has moved on from
     *                                  {@code expectedVersion}
     */
    @Transactional
    public int updateAddress(
            UUID tenantId,
            UUID accountId,
            UUID addressId,
            int expectedVersion,
            String label,
            AddressFields fields,
            @Nullable String deliveryInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource) {

        requireCoordinatesMatchSource(coordinateSource, latitude, longitude);
        var current = store.address(tenantId, accountId, addressId).orElseThrow(AddressNotFoundException::new);

        String document = objectMapper.writeValueAsString(fields);
        String encryptedFields = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef(ADDRESS_TABLE, "encrypted_fields", addressId),
                        document)
                .serialize();

        String encryptedInstructions = deliveryInstructions == null
                ? null
                : protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef(ADDRESS_TABLE, "delivery_instructions_encrypted", addressId),
                                deliveryInstructions)
                        .serialize();

        int written = store.updateAddress(
                tenantId,
                accountId,
                addressId,
                expectedVersion,
                label,
                encryptedFields,
                encryptedInstructions,
                latitude,
                longitude,
                coordinateSource.name(),
                clock.instant());
        if (written == 0) {
            // The row was there a statement ago, so this is a version disagreement
            // rather than an absence: either the caller read an older copy, or a
            // second tab won the race between the read above and this write.
            throw new StaleRecordException(expectedVersion, current.version());
        }
        return expectedVersion + 1;
    }

    /**
     * Retires one of an account's own addresses.
     *
     * <p>Archive, not delete — see {@code JdbcCustomerStore.archiveAddress} for why
     * the row stays. An archived address disappears from every list and can no
     * longer be chosen as a destination, and every cart and order that already
     * copied it is untouched.
     *
     * @return the stored version after the write
     */
    @Transactional
    public int archiveAddress(UUID tenantId, UUID accountId, UUID addressId, int expectedVersion) {
        var current = store.address(tenantId, accountId, addressId).orElseThrow(AddressNotFoundException::new);
        if (store.archiveAddress(tenantId, accountId, addressId, expectedVersion, clock.instant()) == 0) {
            throw new StaleRecordException(expectedVersion, current.version());
        }
        return expectedVersion + 1;
    }

    /** The account's own profile row, or empty when it is not this tenant's. */
    @Transactional(readOnly = true)
    public Optional<JdbcCustomerStore.AccountRow> profile(UUID tenantId, UUID accountId) {
        return store.account(tenantId, accountId);
    }

    /**
     * Writes the fields a customer may change about themselves.
     *
     * <p>Three, and the list is short on purpose: a display name, a language, and
     * a timezone. Status, partition, merge target and policy version all decide
     * something about the account and none of them is the account holder's to set —
     * {@code JdbcCustomerStore.updateAccountProfile} never names those columns, so
     * they cannot be reached from here even by a field somebody adds to a request
     * later.
     *
     * <p>A blank string is stored as absent. "" and null would otherwise be two
     * spellings of "no preference", and the notification path that reads
     * {@code preferred_locale} would try to resolve a bundle for the empty locale.
     *
     * @return the stored version after the write
     */
    @Transactional
    public int updateProfile(
            UUID tenantId,
            UUID accountId,
            int expectedVersion,
            @Nullable String displayName,
            @Nullable String preferredLocale,
            @Nullable String preferredTimezone) {

        var current = store.account(tenantId, accountId).orElseThrow(AccountNotFoundException::new);
        if (store.updateAccountProfile(
                        tenantId,
                        accountId,
                        expectedVersion,
                        blankToNull(displayName),
                        blankToNull(preferredLocale),
                        blankToNull(preferredTimezone),
                        clock.instant())
                == 0) {
            throw new StaleRecordException(expectedVersion, current.version());
        }
        return expectedVersion + 1;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private RevealedAddress reveal(UUID tenantId, JdbcCustomerStore.AddressRow row, String purpose) {
        return new RevealedAddress(
                row.id(),
                row.label(),
                objectMapper.readValue(
                        protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(row.encryptedFields()),
                                new RecordRef(ADDRESS_TABLE, "encrypted_fields", row.id()),
                                purpose),
                        AddressFields.class),
                row.encryptedInstructions() == null
                        ? null
                        : protection.reveal(
                                tenantId,
                                ProtectedValue.deserialize(row.encryptedInstructions()),
                                new RecordRef(ADDRESS_TABLE, "delivery_instructions_encrypted", row.id()),
                                purpose),
                row.latitude(),
                row.longitude(),
                CoordinateSource.valueOf(row.coordinateSource()),
                row.version());
    }

    /** No such address of this account's — the same answer as "not yours". */
    public static class AddressNotFoundException extends RuntimeException {
        public AddressNotFoundException() {
            super("No such address");
        }
    }

    /** No such account in this tenant. */
    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException() {
            super("No such customer account");
        }
    }

    /** The caller's expected version no longer matches the stored row. */
    public static class StaleRecordException extends RuntimeException {

        private final int expected;
        private final int actual;

        public StaleRecordException(int expected, int actual) {
            super("Expected version %d but the row is at %d".formatted(expected, actual));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    private void requireAccount(UUID tenantId, UUID accountId) {
        if (!store.accountExists(tenantId, accountId)) {
            throw new IllegalArgumentException("No such customer account in this tenant");
        }
    }

    /**
     * Canonicalises before hashing.
     *
     * <p>Without this, {@code +998 90 111-22-33} and {@code +998901112233} hash
     * differently and a support agent searching for a customer finds nobody.
     */
    private static String normalize(ContactType type, String rawValue) {
        // rawValue is declared non-null, but this guarded null check preserves what the
        // Optional-based version it replaces did for a value that got here anyway.
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("A contact value is required");
        }

        return switch (type) {
            // Delegated rather than inlined, because the ADR 0015 verification path
            // hashes a number under the same domain: two spellings of one rule
            // would mean a number proved by OTP silently failing to match the
            // contact point somebody had already stored for it.
            case PHONE -> PhoneNumber.normalize(rawValue);
            case EMAIL -> rawValue.strip().toLowerCase(Locale.ROOT);
        };
    }

    public enum ContactType {
        PHONE,
        EMAIL;

        /** Separates hash domains so a phone and an email cannot collide. */
        String lookupDomain() {
            return "customer.contact." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Why an address does or does not carry a point (ADR 0015).
     *
     * <p>Exists because a null coordinate pair had two meanings and no way to
     * tell them apart. "Nobody has geocoded this yet" is retryable;
     * {@link #LANDMARK_ONLY} is finished. A backfill that cannot distinguish
     * them either re-queries every landmark address forever or stops retrying
     * the ones a provider outage left empty.
     */
    public enum CoordinateSource {
        /** Not attempted, or attempted and failed. Worth retrying. */
        NOT_GEOCODED(false),

        /**
         * Deliberately no point. A mahalla house given by its ориентир
         * (landmark) is a complete address in this market; dispatch reaches it
         * by calling, and re-queueing it for geocoding wastes provider calls
         * forever without ever succeeding.
         */
        LANDMARK_ONLY(false),

        /** Resolved by the ADR 0015 geocoding port. */
        GEOCODER(true),

        /** The customer dropped a pin on a map. */
        CUSTOMER_PIN(true),

        /** An operator placed the pin, usually while on the phone. */
        OPERATOR_PIN(true),

        /**
         * A point that predates this column, whose provenance nobody recorded.
         * Readable but never written: {@link #addAddress} refuses it, so a new
         * row cannot claim an unknown origin, and a provenance audit can still
         * find exactly the rows that have one.
         */
        LEGACY_UNSOURCED(true);

        private final boolean requiresPoint;

        CoordinateSource(boolean requiresPoint) {
            this.requiresPoint = requiresPoint;
        }

        /** Whether a coordinate pair must accompany this source. */
        public boolean requiresPoint() {
            return requiresPoint;
        }
    }

    /**
     * The address itself, encrypted as one document (ADR 0015, ADR 0029).
     *
     * <p>подъезд, этаж and ориентир are separate fields rather than free text
     * because a courier standing in a Soviet-era block cannot find a flat from a
     * street line: the entrance and floor are what actually locate a door, and
     * for a large share of addresses here the landmark is the only thing that
     * locates the building. Folded into one line they cannot be shown to a
     * courier as a checklist, carried to a partner adapter that has its own
     * fields for them, or corrected without re-typing the whole address.
     *
     * <p>All of it stays inside the encrypted document. These fields describe
     * where one identified person lives, and a clear column would put them in
     * every backup, replica and export.
     *
     * @param entrance подъезд
     * @param floor этаж
     * @param landmark ориентир — "opposite the pharmacy, blue gate"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddressFields(
            @jakarta.validation.constraints.Size(max = 200) String line1,

            @jakarta.validation.constraints.Size(max = 200) @Nullable
            String line2,

            @jakarta.validation.constraints.Size(max = 120) String city,
            @jakarta.validation.constraints.Size(max = 120) String district,

            @jakarta.validation.constraints.Size(max = 32) @Nullable
            String postalCode,

            @jakarta.validation.constraints.Size(max = 32) @Nullable
            String entrance,

            @jakarta.validation.constraints.Size(max = 32) @Nullable
            String floor,

            @jakarta.validation.constraints.Size(max = 32) @Nullable
            String apartment,

            @jakarta.validation.constraints.Size(max = 300) @Nullable
            String landmark) {}

    public record RevealedContact(
            UUID id, ContactType type, String value, String verificationStatus, boolean isPrimary) {}

    /** A contact point named by kind and state, with no value and therefore no decrypt. */
    public record ContactPointSummary(UUID id, ContactType type, String verificationStatus, boolean isPrimary) {}

    /**
     * An address as revealed to its owner.
     *
     * @param version the row's optimistic-concurrency version, so a customer
     *                editing their own address can present it as an
     *                {@code If-Match} precondition and lose loudly to a second tab
     */
    public record RevealedAddress(
            UUID id,
            String label,
            AddressFields fields,
            @Nullable String deliveryInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource,
            int version) {}
}
