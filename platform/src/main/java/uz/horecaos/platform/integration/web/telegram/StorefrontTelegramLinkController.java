package uz.horecaos.platform.integration.web.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotIdentityResolver;
import uz.horecaos.platform.integration.provider.telegram.TelegramBotIdentityResolver.Installation;
import uz.horecaos.platform.integration.provider.telegram.TelegramCustomerLinkService;
import uz.horecaos.platform.integration.provider.telegram.TelegramMiniAppInitDataVerifier;
import uz.horecaos.platform.integration.provider.telegram.TelegramMiniAppInitDataVerifier.Verified;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A customer's own Telegram link to the brand's bot (ADR 0058 stage 2).
 *
 * <p>Two ways in, both ending at {@link TelegramCustomerLinkService#link}: a
 * {@code /start <code>} deep link, minted here and redeemed later by
 * {@code TelegramUpdateHandler} when Telegram delivers the resulting update;
 * or a verified Mini App {@code initData} payload, which links in this one
 * call with no code and no second round trip at all. Which the storefront
 * offers is a client concern — a Mini App context can use either, a plain
 * browser only the first.
 *
 * <p>Authorised by ownership, like every other storefront endpoint on this
 * account: no ADR 0025 capability, because a customer linking their own chat
 * is not exercising delegated authority over anything.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/telegram")
@Tag(
        name = "Customer Telegram linking",
        description = "A customer's own 1:1 chat with the brand's bot (ADR 0058 stage 2)")
public class StorefrontTelegramLinkController {

    private final CurrentCustomer currentCustomer;
    private final TelegramCustomerLinkService customerLinks;
    private final TelegramBotIdentityResolver botIdentity;
    private final TelegramMiniAppInitDataVerifier initDataVerifier;
    private final SecretResolver secrets;
    private final Clock clock;

    public StorefrontTelegramLinkController(
            CurrentCustomer currentCustomer,
            TelegramCustomerLinkService customerLinks,
            TelegramBotIdentityResolver botIdentity,
            TelegramMiniAppInitDataVerifier initDataVerifier,
            SecretResolver secrets,
            Clock clock) {
        this.currentCustomer = currentCustomer;
        this.customerLinks = customerLinks;
        this.botIdentity = botIdentity;
        this.initDataVerifier = initDataVerifier;
        this.secrets = secrets;
        this.clock = clock;
    }

    @PostMapping("/link-codes")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Mint a Telegram deep-link code",
            description = "Open the returned link (or send \"/start <code>\" to the bot directly) to link "
                    + "this brand's bot to your account. The code expires shortly and is single-use; opening "
                    + "it links immediately, with no further confirmation.")
    public ResponseEntity<CustomerTelegramLinkCodeResponse> issueCode(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        String username = requireBotUsername(tenantId);
        String code = customerLinks.issueCode(tenantId, brandId, accountId);
        return ResponseEntity.ok(
                new CustomerTelegramLinkCodeResponse(code, "https://t.me/%s?start=%s".formatted(username, code)));
    }

    @GetMapping("/link")
    @CustomerOwned
    @Operation(summary = "Whether this account currently has a linked Telegram chat")
    public ResponseEntity<CustomerTelegramLinkStatusResponse> status(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        boolean linked = customerLinks.activeBinding(tenantId, accountId).isPresent();
        return ResponseEntity.ok(new CustomerTelegramLinkStatusResponse(linked));
    }

    @DeleteMapping("/link")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Unlink the customer's Telegram chat",
            description = "Retires the binding and its endpoint. Unlinking what was never linked is the "
                    + "state this call asked for, so it always succeeds.")
    public ResponseEntity<Void> unlink(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        customerLinks.unlink(tenantId, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mini-app-link")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Link via a verified Telegram Mini App session",
            description = "Verifies window.Telegram.WebApp.initData against the brand bot's own token "
                    + "(HMAC-SHA-256, Telegram's official construction) and links immediately — no /start, "
                    + "no code, no second round trip.")
    public ResponseEntity<CustomerTelegramLinkStatusResponse> linkViaMiniApp(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody MiniAppLinkRequest body) {
        UUID accountId = accountId(tenantId, brandId);
        Installation installation = requireInstallation(tenantId);
        String token = secrets.resolve(SecretReference.parse(installation.secretReference()))
                .reveal();

        Verified verified = initDataVerifier
                .verify(body.initData(), token)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "initData could not be verified"));

        // Telegram's own fact, not an assumption this code makes: a 1:1
        // private chat's id equals the user's own id, so there is no update
        // to wait for the way the /start path waits for one from the
        // webhook — the chat already exists the moment the user does.
        customerLinks.link(
                tenantId,
                installation.id(),
                brandId,
                accountId,
                verified.telegramUserId(),
                verified.telegramUserId(),
                clock.instant());

        return ResponseEntity.ok(new CustomerTelegramLinkStatusResponse(true));
    }

    private String requireBotUsername(UUID tenantId) {
        Installation installation = requireInstallation(tenantId);
        return botIdentity
                .resolveUsername(tenantId, installation)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_ERROR, "Could not reach Telegram to resolve the bot's own identity"));
    }

    private Installation requireInstallation(UUID tenantId) {
        return botIdentity
                .activeInstallation(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.UNPROCESSABLE_STATE, "No Telegram bot is configured yet"));
    }

    /**
     * Refuses an account that is not the caller's, as not found — the same
     * choice {@code CustomerNotificationPreferenceController} makes and for
     * the same reason: a forbidden answer would confirm that the account id
     * names a real customer to anyone who guessed it.
     */
    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    public record CustomerTelegramLinkCodeResponse(String code, String deepLink) {}

    public record CustomerTelegramLinkStatusResponse(boolean linked) {}

    public record MiniAppLinkRequest(@NotBlank String initData) {

        /** A record's generated {@code toString} would print the raw initData, including the customer's Telegram user id. */
        @Override
        public String toString() {
            return "MiniAppLinkRequest[initData=<redacted>]";
        }
    }
}
