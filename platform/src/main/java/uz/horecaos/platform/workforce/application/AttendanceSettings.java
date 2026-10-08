package uz.horecaos.platform.workforce.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.api.WorkforceConfigurationKeys;

/**
 * The tenant's attendance policy (ADR 0156, Policy keys), resolved through ADR 0030 and nowhere else.
 *
 * <p>Off until a tenant opts in ({@code workforce.attendance.enabled}); while it is false every
 * workforce route answers {@code RESOURCE_NOT_FOUND}, "as routes that do not exist for this tenant",
 * the export refuses as an unknown report, and nothing here is a legal working-time rule.
 */
@Component
public class AttendanceSettings {

    private final ConfigurationResolver configuration;

    public AttendanceSettings(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    public boolean enabled(UUID tenantId) {
        return Boolean.TRUE.equals(
                configuration.value(WorkforceConfigurationKeys.ATTENDANCE_ENABLED, ResourceScope.tenant(tenantId)));
    }

    /**
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the tenant has not opted in: the same answer
     *     an unknown route gets, so a tenant that has not turned it on cannot tell the module exists
     */
    public void requireEnabled(UUID tenantId) {
        if (!enabled(tenantId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such resource.");
        }
    }

    public int varianceThresholdMinutes(Branch branch) {
        Integer value = configuration.value(WorkforceConfigurationKeys.VARIANCE_THRESHOLD_MINUTES, branch.scope());
        return value == null ? 30 : value;
    }

    public int maxOpenHours(Branch branch) {
        Integer value = configuration.value(WorkforceConfigurationKeys.MAX_OPEN_HOURS, branch.scope());
        return value == null ? 14 : value;
    }

    public int lateAfterMinutes(Branch branch) {
        Integer value = configuration.value(WorkforceConfigurationKeys.LATE_AFTER_MINUTES, branch.scope());
        return value == null ? 10 : value;
    }

    public int matchWindowMinutes(UUID tenantId) {
        Integer value =
                configuration.value(WorkforceConfigurationKeys.MATCH_WINDOW_MINUTES, ResourceScope.tenant(tenantId));
        return value == null ? 240 : value;
    }

    public int retroactiveEditDays(UUID tenantId) {
        Integer value =
                configuration.value(WorkforceConfigurationKeys.RETROACTIVE_EDIT_DAYS, ResourceScope.tenant(tenantId));
        return value == null ? 7 : value;
    }

    public boolean approverMustDiffer(UUID tenantId) {
        return Boolean.TRUE.equals(
                configuration.value(WorkforceConfigurationKeys.APPROVER_MUST_DIFFER, ResourceScope.tenant(tenantId)));
    }
}
