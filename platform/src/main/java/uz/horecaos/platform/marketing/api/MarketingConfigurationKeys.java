package uz.horecaos.platform.marketing.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The marketing module's ADR 0030 configuration keys (ADR 0112).
 *
 * <p><strong>Declared twice</strong>, the discipline {@code
 * notifications.api.NotificationConfigurationKeys} documents: this declaration is what
 * a caller in this module reads, and {@code
 * uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys} carries an
 * identical one for the startup validator, since importing across that boundary would
 * make the two modules cyclic. {@code MarketingConfigurationKeyTests} fails the build
 * if they drift.
 *
 * <p>The contact policy's own tenant overrides are not keys. ADR 0112 calls them
 * "ADR 0030-resolved values at BRAND scope", and what a key can hold (one scalar at a
 * scope) is narrower than what an override says (a cap and a quiet window, per channel,
 * purpose and period), so they are rows in {@code marketing.contact_policy_overrides},
 * resolved most-specific-wins over the platform default and tighten-only, exactly as
 * {@code marketing.engagement_policies} already is.
 */
public final class MarketingConfigurationKeys {

    public static final String CHANNEL_PRIORITY_ORDER_CODE = "marketing.channel.priority.order";

    /**
     * A comma-separated ranking of campaign purposes, most important first: what
     * resolves a tie between a scenario step and a broadcast due for the same guest.
     */
    public static final ConfigurationKey<String> CHANNEL_PRIORITY_ORDER = ConfigurationKey.of(
                    CHANNEL_PRIORITY_ORDER_CODE, String.class)
            .defaultValue("")
            .ownedBy("marketing")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Which campaign purpose is sent first when a scenario step and a broadcast are both "
                    + "due for one guest: a comma-separated list of consent purposes, most important first.")
            .build();

    public static final String IN_APP_SHOW_CAP_PER_DAY_CODE = "marketing.in_app.show_cap_per_day";

    public static final ConfigurationKey<Integer> IN_APP_SHOW_CAP_PER_DAY = ConfigurationKey.of(
                    IN_APP_SHOW_CAP_PER_DAY_CODE, Integer.class)
            .defaultValue(3)
            .ownedBy("marketing")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("The most times a guest is shown one in-app banner in a day. A banner has no "
                    + "delivery attempt to count against the messaging frequency cap, so it has its own.")
            .build();

    public static final String SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT_CODE =
            "marketing.scenario.control_group_percent.default";

    public static final ConfigurationKey<Integer> SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT = ConfigurationKey.of(
                    SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT_CODE, Integer.class)
            .defaultValue(10)
            .ownedBy("marketing")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("The share of a scenario's audience withheld as a control group that the "
                    + "authoring form offers by default. A scenario may set its own, or none.")
            .build();

    private MarketingConfigurationKeys() {}
}
