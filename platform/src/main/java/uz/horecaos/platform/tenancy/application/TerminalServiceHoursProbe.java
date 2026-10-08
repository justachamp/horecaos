package uz.horecaos.platform.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.terminals.TerminalServiceHours;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.Serviceability;
import uz.horecaos.platform.tenancy.api.ServiceabilityReason;
import uz.horecaos.platform.tenancy.api.ServiceabilityResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Answers {@code iam}'s "is this branch in service" (ADR 0155) from the one place that knows: the serviceability resolver
 * (ADR 0036), asked about the active channels that sell at the branch. A branch is in service when any of them may take an
 * order for immediate service; a branch that sells through no active channel is never in service, so a tablet at a branch
 * that does not trade raises nothing.
 *
 * <p>This adds no rule. It asks the resolver for each channel with the first fulfilment mode the channel enables, in a
 * fixed order, exactly as the kiosk's configuration read does, and stops at the first channel that may sell or is full.
 */
@Component
class TerminalServiceHoursProbe implements TerminalServiceHours {

    private static final List<FulfillmentMode> ORDER =
            List.of(FulfillmentMode.DINE_IN, FulfillmentMode.PICKUP, FulfillmentMode.DELIVERY);

    private final JdbcSalesChannelStore channels;
    private final ServiceabilityResolver serviceability;

    TerminalServiceHoursProbe(JdbcSalesChannelStore channels, ServiceabilityResolver serviceability) {
        this.channels = channels;
        this.serviceability = serviceability;
    }

    @Override
    public boolean inService(UUID tenantId, UUID brandId, UUID locationId, Instant at) {
        Map<UUID, List<FulfillmentMode>> modes = channels.enabledFulfillmentModesByChannel(tenantId);
        for (UUID channelId : channels.activeChannelIdsAt(tenantId, locationId)) {
            List<FulfillmentMode> enabled = modes.getOrDefault(channelId, List.of());
            for (FulfillmentMode mode : ORDER) {
                if (enabled.contains(mode)) {
                    Serviceability answer = serviceability.resolve(tenantId, brandId, locationId, channelId, mode, at);
                    // A kitchen at its ceiling is as much in service as one with room: the tablet is the busiest screen
                    // there is, and the one whose silence matters most.
                    if (answer.available() || answer.reason() == ServiceabilityReason.AT_CAPACITY) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }
}
