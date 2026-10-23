package uz.horecaos.platform.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLanding;
import uz.horecaos.platform.integration.provider.telegram.TelegramInstallationBrandLookup;
import uz.horecaos.platform.marketing.api.AttributionClickPort;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcStorefrontHostnames;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTelegramChannelSettingsStore;

/**
 * The {@link TelegramCustomerLanding} a suite that is about something else hands {@code TelegramUpdateHandler} (ADR 0183).
 *
 * <p>Every payload is "not a link", so a suite that sends {@code /start <code>} still gets the refusal it was written for,
 * and the channel settings and storefront hostnames are the real JDBC ones over the suite's own database, so a bot whose
 * Telegram channel the suite never configured stays as silent as it was.
 */
public final class TelegramLandings {

    private TelegramLandings() {}

    public static TelegramCustomerLanding inert(JdbcClient jdbc) {
        AttributionClickPort neverALink =
                (tenantId, brandId, token, visitor) -> AttributionClickPort.Outcome.NOT_A_LINK;
        return new TelegramCustomerLanding(
                neverALink,
                new JdbcTelegramChannelSettingsStore(jdbc),
                new JdbcStorefrontHostnames(jdbc),
                BrandLocaleLookup.platformFallback(),
                new TelegramInstallationBrandLookup(jdbc),
                new TelegramBotApiClient(JsonMapper.builder().build()));
    }
}
