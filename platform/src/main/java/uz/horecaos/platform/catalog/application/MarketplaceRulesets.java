package uz.horecaos.platform.catalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The closed, code-owned set of marketplace rulesets (ADR 0138).
 *
 * <p>Empty in production today, and that is the decision rather than a gap: the
 * per-marketplace rules are not this record's to write. The registry is what
 * makes "empty" a state the platform can reason about — a binding that names a
 * code absent from here is reported as {@code MARKETPLACE_RULESET_UNKNOWN} rather
 * than silently passing — and what a new ruleset registers into by being a
 * Spring bean.
 *
 * <p>Collected through an {@link ObjectProvider} because Spring refuses to inject
 * an empty {@code List} into a required collection point, and empty is the
 * configuration this class is built for.
 */
@Component
public class MarketplaceRulesets {

    private final Map<String, MarketplaceRuleset> byCode;

    @Autowired
    public MarketplaceRulesets(ObjectProvider<MarketplaceRuleset> rulesets) {
        this(rulesets.orderedStream().toList());
    }

    /** For callers with no Spring context — chiefly the tests that prove the plug point. */
    public MarketplaceRulesets(Collection<MarketplaceRuleset> rulesets) {
        Map<String, MarketplaceRuleset> collected = new TreeMap<>();
        for (MarketplaceRuleset ruleset : rulesets) {
            MarketplaceRuleset clash = collected.put(ruleset.code(), ruleset);
            if (clash != null) {
                // Two rulesets under one code would make "which rules ran" depend on bean order.
                throw new IllegalStateException("Two marketplace rulesets claim the code " + ruleset.code());
            }
        }
        this.byCode = Map.copyOf(collected);
    }

    /** No rulesets at all — what production runs today. */
    public static MarketplaceRulesets none() {
        return new MarketplaceRulesets(List.<MarketplaceRuleset>of());
    }

    public Optional<MarketplaceRuleset> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** The codes a binding may name; empty until a ruleset is authored. */
    public Set<String> knownCodes() {
        return byCode.keySet();
    }
}
