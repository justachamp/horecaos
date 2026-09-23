package uz.horecaos.platform.pricing.application;

import java.util.UUID;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pricing.api.PricingConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;

/**
 * {@code catalog.qr_kiosk_price_plane} (gap map row {@code 4.4d}, wave w7):
 * a {@code QR_TABLE} or {@code KIOSK} channel with no price plane of its own
 * takes the tenant's hall channel's prices when the switch is on and the
 * tenant has registered exactly one active {@code POS} channel — ADR 0036's
 * own vocabulary correction is that dine-in is a fulfilment mode, never a
 * channel, and {@code POS} is the waiter-entered channel that carries it.
 *
 * <p>Shared by {@link QuoteService} (an actual cart) and {@link
 * PriceQueryService} (the price-preview read), which must agree: a preview
 * that showed one price and a checkout that charged another would be a
 * silent bug nobody could reproduce from the receipt. A single resolver is
 * what makes that agreement structural rather than a promise two copies keep
 * by coincidence.
 *
 * <p>A channel an operator already pointed somewhere by hand — {@link
 * SalesChannel#pricePlaneChannelId()} non-null — is never touched here, flag
 * or no flag: manual configuration always outranks this default, exactly as
 * {@code ConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE}'s own doc promises.
 * Every other channel type, and a tenant with zero or several {@code POS}
 * channels, resolves exactly as it did before this switch existed — {@link
 * SalesChannelLookup#hallChannelId(UUID)}'s own doc names why zero or several
 * is left alone rather than guessed at.
 */
final class QrKioskHallPricing {

    private QrKioskHallPricing() {}

    static UUID resolve(
            UUID tenantId, SalesChannel channel, SalesChannelLookup channels, ConfigurationResolver configuration) {
        if (channel.pricePlaneChannelId() != null
                || (channel.systemType() != SalesChannelSystemType.QR_TABLE
                        && channel.systemType() != SalesChannelSystemType.KIOSK)) {
            return channel.pricingChannelId();
        }
        Boolean qrKioskHallPricing = configuration.value(
                PricingConfigurationKeys.CATALOG_QR_KIOSK_PRICE_PLANE, ResourceScope.tenant(tenantId));
        if (!Boolean.TRUE.equals(qrKioskHallPricing)) {
            return channel.pricingChannelId();
        }
        return channels.hallChannelId(tenantId).orElseGet(channel::pricingChannelId);
    }
}
