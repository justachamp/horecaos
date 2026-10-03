package uz.horecaos.platform.catalog.application;

import java.util.List;
import uz.horecaos.platform.catalog.domain.ValidationFinding;

/**
 * One marketplace's content rules, as a plug point (ADR 0138).
 *
 * <p>Image resolution, description length, category depth and price parity for
 * Yandex Eda, Uzum Tezkor, Wolt and Express24 are an open input of the record —
 * a product and partnerships question, not an architecture one — so the
 * mechanism ships with <strong>no</strong> implementation of this interface.
 * Onboarding the first real marketplace is then one class that implements it and
 * one code in {@code integration.bindings.marketplace_ruleset_code}, with no
 * schema change.
 *
 * <p>Deliberately <em>not</em> folded into {@code CatalogValidator}'s own closed
 * rule set. Those rules are universal — every channel, every publication — and a
 * rule one marketplace enforces may not apply to another; hard-coding partner
 * logic into the validator every publication path shares would make every channel
 * pay for every partner's idiosyncrasies (ADR 0138, alternatives considered).
 *
 * <p>Implementations are pure over the projection: no database, no clock, no
 * service calls, so every rule is testable on a literal — the discipline {@code
 * CatalogValidator} itself keeps.
 */
public interface MarketplaceRuleset {

    /**
     * The code a binding names this ruleset by: upper-case letters, digits and
     * underscores, as {@code ck_binding_marketplace_ruleset_code} requires.
     */
    String code();

    /**
     * The findings this ruleset raises over what a channel would receive.
     *
     * <p>Every finding's code must start with {@code MARKETPLACE_} — the family
     * ADR 0138 reserves — so a client can tell a partner's rule from a universal
     * one by its code alone. {@code CatalogValidator#marketplaceFindings} refuses
     * a finding that does not, because a ruleset that emitted {@code
     * PRODUCT_HAS_NO_ACTIVE_VARIANT} would be impersonating a rule it does not own.
     */
    List<ValidationFinding> check(ChannelProjection projection);
}
