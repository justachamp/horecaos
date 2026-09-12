/**
 * Plans, subscriptions, entitlements, the append-only usage ledger, and — as
 * of ADR 0095 — each tenant's wallet.
 *
 * <p>Named {@code commercial} rather than {@code subscription} because ADR 0021
 * names it so, because {@link uz.horecaos.platform.iam.api.Capability} already
 * carries {@code commercial.subscription.manage} and
 * {@code commercial.override.approve}, and because a subscription is one of the
 * five things in here rather than the thing itself: a plan catalogue, a
 * subscription, an entitlement resolution, a usage ledger and a wallet are not
 * all subscriptions, and the module that owns a meter should not be named after
 * the row the meter is checked against.
 *
 * <p>What a tenant is owed to pay HorecaOS, and what it has paid or been
 * granted: both live here (ADR 0088, ADR 0095). ADR 0013 still owns a
 * different kind of money entirely — a tenant's <em>own customers</em> paying
 * the tenant through the tenant's own merchant account — which never crosses
 * into this module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Commercial")
package uz.horecaos.platform.commercial;
