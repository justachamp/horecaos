package uz.horecaos.platform.integration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.integration.provider.telegram.TelegramMiniAppSetupService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The guided Mini App and BotFather setup (ADR 0183, gap-map row {@code 6.6a}).
 *
 * <p>{@code GET} derives every step from the facts and stores nothing, so the flow resumes wherever it was left.
 * {@code POST .../menu-button} is the one thing the platform does on the tenant's behalf: it sets the bot's menu button to
 * open the brand's verified storefront and reads it back. It declares {@code integration.installation.manage}, the grant
 * that connected the bot, at the brand: an owner or admin holds it everywhere, and nobody gets it by being a marketer.
 *
 * <p>Nothing here accepts a URL or a token. The Mini App address is derived from the brand's verified hostname, and the
 * bot's token never leaves the secrets manager.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/telegram/mini-app-setup")
@Tag(name = "Telegram Mini App setup", description = "The guided Mini App and BotFather setup (ADR 0183)")
public class TelegramMiniAppSetupController {

    private final TelegramMiniAppSetupService setup;
    private final CurrentActor currentActor;

    public TelegramMiniAppSetupController(TelegramMiniAppSetupService setup, CurrentActor currentActor) {
        this.setup = setup;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "Where this brand stands in the Mini App setup, derived from the facts",
            description = "Steps: CONNECT_BOT, REGISTER_WEBHOOK, TELEGRAM_CHANNEL, STOREFRONT_ADDRESS, BOTFATHER_APP, "
                    + "MENU_BUTTON, TEST_LINK; each DONE, TODO or UNKNOWN. BOTFATHER_APP is «recorded», never "
                    + "verified: Telegram has no API for /newapp. MENU_BUTTON is read back from Telegram.")
    public StatusView status(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return StatusView.of(setup.status(tenantId, brandId));
    }

    @PostMapping("/menu-button")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Set the bot's menu button to open the brand's storefront, and read it back",
            description =
                    "Calls Telegram's setChatMenuButton with the label from the Telegram channel's settings and "
                            + "the brand's verified storefront address, then getChatMenuButton. 422 naming what is missing "
                            + "when the bot, the Telegram channel, the Mini App switch or the address is not there, or when "
                            + "Telegram refuses. A Telegram that accepts and then shows something else leaves MENU_BUTTON TODO.")
    public StatusView applyMenuButton(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return StatusView.of(setup.applyMenuButton(
                tenantId, brandId, ActorRef.user(currentActor.get().subject(), null)));
    }

    record StepView(String code, String state, @Nullable String detail) {}

    record StatusView(
            List<StepView> steps,
            @Nullable String botUsername,
            @Nullable String miniAppUrl,
            @Nullable String miniAppShortName,
            @Nullable UUID channelId,
            @Nullable Integer channelVersion,
            @Nullable String menuButtonLabel,
            @Nullable String menuButtonUrl,
            @Nullable String testLink) {

        static StatusView of(TelegramMiniAppSetupService.Status status) {
            return new StatusView(
                    status.steps().stream()
                            .map(step -> new StepView(step.code(), step.state().name(), step.detail()))
                            .toList(),
                    status.botUsername(),
                    status.miniAppUrl(),
                    status.miniAppShortName(),
                    status.channelId(),
                    status.channelVersion(),
                    status.menuButtonLabel(),
                    status.menuButtonUrl(),
                    status.testLink());
        }
    }
}
