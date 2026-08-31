package uz.horecaos.platform.integration.provider.telegram;

import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;

/**
 * Verifies the bot's own rights in a chat before a binding is ever created (ADR
 * 0058: "calls {@code getChatMember} to verify the bot's rights... and fails
 * actionably if absent").
 *
 * <p>Two questions, not one. Every chat needs the bot to be an administrator —
 * a plain member cannot be relied on to keep posting once the group's admins
 * start pruning permissions. A forum topic additionally needs
 * {@code can_manage_topics}, because posting into a topic that gets deleted
 * without that right leaves the binding sending into a void with no way to find
 * out.
 */
@Component
public class TelegramRightsVerifier {

    private static final Set<String> ADMIN_STATUSES = Set.of("administrator", "creator");

    private final TelegramBotApiClient bots;

    public TelegramRightsVerifier(TelegramBotApiClient bots) {
        this.bots = bots;
    }

    public Verification verify(ProviderCall call, long chatId, boolean isTopicScoped) {
        TelegramCallResult me = bots.getMe(call);
        if (!(me instanceof TelegramCallResult.Success meSuccess)) {
            return Verification.failed("Could not identify the bot itself (getMe did not succeed)");
        }
        Object botId = meSuccess.result().get("id");
        if (!(botId instanceof Number botIdNumber)) {
            return Verification.failed("The bot's own account id was missing from getMe");
        }

        TelegramCallResult member = bots.getChatMember(call, chatId, botIdNumber.longValue());
        if (!(member instanceof TelegramCallResult.Success memberSuccess)) {
            return Verification.failed("Could not read the bot's own membership in this chat");
        }

        Map<String, Object> result = memberSuccess.result();
        String status = String.valueOf(result.get("status"));
        if (!ADMIN_STATUSES.contains(status)) {
            return Verification.failed(
                    "The bot must be an administrator of this chat; it is currently \"%s\"".formatted(status));
        }

        if (isTopicScoped && !Boolean.TRUE.equals(result.get("can_manage_topics"))) {
            return Verification.failed(
                    "The bot is an administrator here but lacks \"Manage Topics\", which is required to post "
                            + "into a specific forum topic");
        }

        return Verification.ok();
    }

    public record Verification(boolean sufficient, @Nullable String actionableReason) {

        static Verification ok() {
            return new Verification(true, null);
        }

        static Verification failed(String reason) {
            return new Verification(false, reason);
        }
    }
}
