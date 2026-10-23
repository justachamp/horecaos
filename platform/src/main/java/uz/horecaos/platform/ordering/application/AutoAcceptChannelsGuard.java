package uz.horecaos.platform.ordering.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationWriteGuard;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Refuses an auto-accept channel list that names a channel the tenant does not have (ADR 0177
 * decision 3).
 *
 * <p>{@code ordering.auto_accept_eligible_channels} is read at checkout now, and a code in it that
 * matches no channel is not harmless the way a never-read setting was: a typo would leave the channel
 * the operator meant outside the list, so every one of its orders waits for a person. The shape of the
 * list is {@code ConfigurationValueRules}' (it needs no database); whether each code is a real
 * {@code tenant.sales_channels.code} of the tenant is a rule about the state of the world, which is
 * what {@link ConfigurationWriteGuard} is for. A platform-scope write names no tenant, so there is no
 * registry to check it against and only its shape is held.
 */
@Component
class AutoAcceptChannelsGuard implements ConfigurationWriteGuard {

    private final SalesChannelLookup channels;

    AutoAcceptChannelsGuard(SalesChannelLookup channels) {
        this.channels = channels;
    }

    @Override
    public void beforeSet(ConfigurationKey<?> key, ResourceScope scope, @Nullable Object value, boolean explicitNull) {
        UUID tenantId = scope.tenantId();
        if (!OrderingConfigurationKeys.AUTO_ACCEPT_ELIGIBLE_CHANNELS_CODE.equals(key.code())
                || explicitNull
                || value == null
                || tenantId == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "ALL".equalsIgnoreCase(text)) {
            return;
        }
        List<String> unknown = Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .filter(code -> channels.byCode(tenantId, code).isEmpty())
                .toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "These are not sales channels of this tenant: " + String.join(", ", unknown),
                    Map.of("field", key.code(), "unknownChannelCodes", unknown));
        }
    }
}
