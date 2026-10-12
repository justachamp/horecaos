package uz.horecaos.platform.iam.api.staff;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;

/**
 * A person's name and employee number by member id, for the payroll export file and nowhere else
 * (ADR 0156, ADR 0139).
 *
 * <p>A third read port beside {@link StaffDirectory} and {@link StaffMemberCards}, and not a
 * fourth method on either: {@code StaffDirectory} is keyed by Keycloak subject, answers three
 * questions and no others, and carries no employee number; the shift and roster tables carry
 * {@code staff_member_id} and no subject, so none of its methods has a key to be called with.
 * {@code StaffMemberCards} is keyed by member id but returns a name and a phone, not an employee
 * number. Their Javadoc contracts are untouched.
 *
 * <p>Like both, this port performs no authorization: the export route does ({@code
 * staff.shift.export}). An id of another tenant, or one with no answer, is absent from the result.
 * The implementation decrypts once per distinct member of an export (hundreds, not once per row),
 * and the result belongs in the file that route authorised and nowhere else -- not a log line, a
 * metric, an event or an audit fact.
 */
public interface StaffPayrollIdentity {

    Map<UUID, PayrollIdentity> identityOf(UUID tenantId, Collection<UUID> memberIds);

    /** Personal data (ADR 0029): the generated {@code toString} is overridden so it can never be printed. */
    record PayrollIdentity(
            @Classified(DataClass.PERSONAL) @Nullable String name,
            @Classified(DataClass.PERSONAL) @Nullable String employeeNumber) {

        @Override
        public String toString() {
            return "PayrollIdentity[<redacted>]";
        }
    }
}
