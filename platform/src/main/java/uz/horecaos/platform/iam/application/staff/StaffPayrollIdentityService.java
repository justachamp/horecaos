package uz.horecaos.platform.iam.application.staff;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.staff.StaffPayrollIdentity;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffMemberStore.MemberRow;

/**
 * {@link StaffPayrollIdentity} over the tenant's own record (ADR 0156, ADR 0139).
 *
 * <p>Every read names the tenant, so a member id of another tenant is simply absent. Only the two
 * columns the payroll file needs are opened (the name and the employee number); the contact phone
 * is not decrypted at all, because nothing here shows it.
 */
@Service
class StaffPayrollIdentityService implements StaffPayrollIdentity {

    private final JdbcStaffMemberStore store;
    private final StaffMemberCodec codec;

    StaffPayrollIdentityService(JdbcStaffMemberStore store, StaffMemberCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @Override
    public Map<UUID, PayrollIdentity> identityOf(UUID tenantId, Collection<UUID> memberIds) {
        Map<UUID, PayrollIdentity> identities = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return identities;
        }
        for (MemberRow row : store.findByIds(tenantId, memberIds)) {
            identities.put(
                    row.id(),
                    new PayrollIdentity(
                            codec.displayName(row),
                            codec.openOrNull(row, StaffMemberCodec.EMPLOYEE_NUMBER, row.protectedEmployeeNumber())));
        }
        return identities;
    }
}
