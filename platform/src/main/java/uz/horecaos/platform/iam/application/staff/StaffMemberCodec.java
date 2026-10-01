package uz.horecaos.platform.iam.application.staff;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;

/**
 * Seals and opens the personal columns of {@code iam.staff_members} (ADR 0139,
 * ADR 0029) and nothing else.
 *
 * <p>Every value is bound by associated data to the tenant, this table, its
 * column and its row, so a ciphertext copied to another row, another column or
 * another tenant fails to decrypt instead of silently showing someone else's
 * name. The keyed lookup hashes are per tenant too: the same phone in two
 * tenants hashes to two unrelated values, so a hash cannot be used to confirm
 * that a number is on another tenant's staff.
 */
@Component
class StaffMemberCodec {

    private static final Logger log = LoggerFactory.getLogger(StaffMemberCodec.class);

    static final String TABLE = "iam.staff_members";
    static final String FIRST_NAME = "protected_first_name";
    static final String LAST_NAME = "protected_last_name";
    static final String PHONE = "protected_phone";
    static final String EMPLOYEE_NUMBER = "protected_employee_number";

    private static final String PHONE_LOOKUP_DOMAIN = "staff.contact_phone";
    private static final String EMPLOYEE_NUMBER_LOOKUP_DOMAIN = "staff.employee_number";
    private static final String PURPOSE = "staff.member.profile";

    private final FieldProtection protection;

    StaffMemberCodec(FieldProtection protection) {
        this.protection = protection;
    }

    String seal(UUID tenantId, UUID memberId, String column, String plaintext) {
        return protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, column, memberId), plaintext)
                .serialize();
    }

    @Nullable
    String sealOrNull(UUID tenantId, UUID memberId, String column, @Nullable String plaintext) {
        return plaintext == null ? null : seal(tenantId, memberId, column, plaintext);
    }

    /**
     * Opens one stored value.
     *
     * @throws ProtectionIntegrityException when the ciphertext does not belong where it was found
     */
    @Nullable
    String open(UUID tenantId, UUID memberId, String column, @Nullable String stored) {
        if (stored == null) {
            return null;
        }
        return protection.reveal(
                tenantId, ProtectedValue.deserialize(stored), new RecordRef(TABLE, column, memberId), PURPOSE);
    }

    /**
     * As {@link #open}, for a screen that must still render when one value does
     * not: a ciphertext that fails its integrity check is a security event, so
     * it is logged -- by the member's non-personal reference, never by value --
     * and the field reads as absent rather than taking the whole list down.
     */
    @Nullable
    String openOrNull(MemberRow row, String column, @Nullable String stored) {
        try {
            return open(row.tenantId(), row.id(), column, stored);
        } catch (ProtectionIntegrityException | IllegalArgumentException failure) {
            log.error(
                    "A protected staff value failed its integrity check ({} of member {} in tenant {})",
                    column,
                    row.displayReference(),
                    row.tenantId());
            return null;
        }
    }

    Plain openAll(MemberRow row) {
        return new Plain(
                openOrNull(row, FIRST_NAME, row.protectedFirstName()),
                openOrNull(row, LAST_NAME, row.protectedLastName()),
                openOrNull(row, PHONE, row.protectedPhone()),
                openOrNull(row, EMPLOYEE_NUMBER, row.protectedEmployeeNumber()));
    }

    /** Names alone: the directory path decrypts nothing it does not show. */
    @Nullable
    String displayName(MemberRow row) {
        String first = openOrNull(row, FIRST_NAME, row.protectedFirstName());
        String last = openOrNull(row, LAST_NAME, row.protectedLastName());
        return joinName(first, last);
    }

    String phoneHash(UUID tenantId, String digits) {
        return protection.lookupHash(tenantId, PHONE_LOOKUP_DOMAIN, digits);
    }

    String employeeNumberHash(UUID tenantId, String normalizedNumber) {
        return protection.lookupHash(tenantId, EMPLOYEE_NUMBER_LOOKUP_DOMAIN, normalizedNumber);
    }

    static @Nullable String joinName(@Nullable String first, @Nullable String last) {
        StringBuilder name = new StringBuilder();
        if (first != null && !first.isBlank()) {
            name.append(first.strip());
        }
        if (last != null && !last.isBlank()) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(last.strip());
        }
        return name.isEmpty() ? null : name.toString();
    }

    /** The opened personal columns of one row. Never printed. */
    record Plain(
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String phone,
            @Nullable String employeeNumber) {

        @Override
        public String toString() {
            return "Plain[<redacted>]";
        }
    }
}
