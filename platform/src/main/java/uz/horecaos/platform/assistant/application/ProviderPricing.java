package uz.horecaos.platform.assistant.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.assistant.api.TokenUsage;

/**
 * What a model call costs, in millionths of a US dollar (V0511's {@code
 * cost_usd_micros}), from the provider's published per-token price.
 *
 * <p>Integer arithmetic only. The provider quotes dollars per million tokens, and
 * one dollar per million tokens is exactly one micro-dollar per token, so the
 * price in this unit is a whole number of micro-dollars per token for every price
 * a provider is likely to quote, and the cost of a turn is {@code tokens *
 * price}, exact. The defaults are the configured model's list prices ($2 in, $10
 * out per million) and are properties, not code, because a model swap changes
 * them and a stale price silently understates spend -- the figure the ceiling
 * protects.
 */
@Component
public class ProviderPricing {

    private final long inputMicrosPerToken;
    private final long outputMicrosPerToken;

    ProviderPricing(
            @Value("${horecaos.assistant.provider.input-usd-micros-per-token:2}") long inputMicrosPerToken,
            @Value("${horecaos.assistant.provider.output-usd-micros-per-token:10}") long outputMicrosPerToken) {
        if (inputMicrosPerToken < 0 || outputMicrosPerToken < 0) {
            throw new IllegalArgumentException("A token price is never negative");
        }
        this.inputMicrosPerToken = inputMicrosPerToken;
        this.outputMicrosPerToken = outputMicrosPerToken;
    }

    public long costUsdMicros(TokenUsage usage) {
        return Math.addExact(
                Math.multiplyExact(usage.inputTokens(), inputMicrosPerToken),
                Math.multiplyExact(usage.outputTokens(), outputMicrosPerToken));
    }
}
