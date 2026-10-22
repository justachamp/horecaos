package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.application.TelegramChannelSettingsService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A TELEGRAM channel's own settings (ADR 0183 D27; row 10.5): the menu button label and welcome text per language,
 * whether the menu button opens the Mini App, for which brand, the BotFather short name, and whether the tenant's terms
 * page is offered as the public offer.
 *
 * <p>Beside {@link ChannelSetupController} under the same path and the same {@code TENANT} scope, in a class of its own so
 * the two facets can change independently. <strong>Nothing here accepts a URL.</strong> The Mini App address and the offer
 * address come back derived from the brand's verified storefront hostname, or with the reason there is none.
 *
 * <p>Saving never calls Telegram. Pushing the menu button to the bot is the guided setup flow's job
 * ({@code TelegramMiniAppSetupController}), so an unreachable Bot API cannot make a saved setting fail.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/sales-channels/{channelId}/setup/telegram")
@Tag(name = "Channel setup", description = "Row 10.5: a Telegram channel's menu button, Mini App and texts")
public class TelegramChannelSetupController {

    private final TelegramChannelSettingsService settings;

    public TelegramChannelSetupController(TelegramChannelSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @RequiresCapability(Capability.CHANNEL_READ)
    @Operation(
            summary = "This Telegram channel's settings, and the addresses derived from them",
            description = "miniAppUrl is the brand's verified storefront hostname, never a value a tenant typed; "
                    + "miniAppUnavailable names why there is none (BRAND_NOT_CHOSEN, HOSTNAME_NOT_CLAIMED, "
                    + "HOSTNAME_NOT_VERIFIED, HOSTNAME_AMBIGUOUS). A channel nobody has configured reads as "
                    + "everything off. 400 for a channel that is not a TELEGRAM channel.")
    public TelegramSettingsView read(@PathVariable UUID tenantId, @PathVariable UUID channelId) {
        return TelegramSettingsView.of(settings.view(tenantId, channelId));
    }

    @PutMapping
    @RequiresCapability(value = Capability.CHANNEL_MANAGE, mutating = true)
    @Operation(
            summary = "Replace this Telegram channel's settings",
            description = "A full replace: an omitted language drops its text and an omitted flag is false. "
                    + "Gated on the channel's version, like every facet of Channel setup. Never calls Telegram.")
    public TelegramSettingsView replace(
            @PathVariable UUID tenantId,
            @PathVariable UUID channelId,
            @Valid @RequestBody TelegramSettingsRequest body,
            @RequestParam int expectedVersion) {
        return TelegramSettingsView.of(settings.update(
                tenantId,
                channelId,
                new TelegramChannelSettingsService.Command(
                        body.brandId(),
                        Boolean.TRUE.equals(body.miniAppEnabled()),
                        body.miniAppShortName(),
                        Boolean.TRUE.equals(body.publicOfferLink()),
                        body.menuButtonLabels() == null ? Map.of() : body.menuButtonLabels(),
                        body.welcomeTexts() == null ? Map.of() : body.welcomeTexts()),
                expectedVersion));
    }

    /**
     * @param miniAppEnabled boxed, with a stated default of false, because a missing primitive in a request body is a
     *                       400 under Jackson 3
     */
    record TelegramSettingsRequest(
            @Nullable UUID brandId,
            @Nullable Boolean miniAppEnabled,
            @Nullable @Size(max = 64) String miniAppShortName,
            @Nullable Boolean publicOfferLink,
            @Nullable Map<String, String> menuButtonLabels,
            @Nullable Map<String, String> welcomeTexts) {}

    /**
     * @param channelVersion the token a write must present as {@code expectedVersion}
     * @param miniAppUrl derived from the brand's verified storefront hostname; null with {@code miniAppUnavailable} set
     * @param publicOfferUrl the tenant's terms page on the same hostname, when the offer link is on and the hostname resolves
     */
    record TelegramSettingsView(
            @Nullable UUID brandId,
            boolean miniAppEnabled,
            @Nullable String miniAppShortName,
            boolean publicOfferLink,
            Map<String, String> menuButtonLabels,
            Map<String, String> welcomeTexts,
            @Nullable String miniAppUrl,
            @Nullable String miniAppUnavailable,
            @Nullable String publicOfferUrl,
            int channelVersion) {

        static TelegramSettingsView of(TelegramChannelSettingsService.View view) {
            TelegramChannelSettings s = view.settings();
            return new TelegramSettingsView(
                    s.brandId(),
                    s.miniAppEnabled(),
                    s.miniAppShortName(),
                    s.publicOfferLink(),
                    s.menuButtonLabels(),
                    s.welcomeTexts(),
                    view.miniAppUrl(),
                    view.miniAppUnavailable(),
                    view.publicOfferUrl(),
                    s.channelVersion());
        }
    }
}
