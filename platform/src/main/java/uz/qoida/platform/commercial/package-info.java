/**
 * Plans, subscriptions, entitlements, and the append-only usage ledger.
 *
 * <p>Named {@code commercial} rather than {@code subscription} because ADR 0021
 * names it so, because {@link uz.qoida.platform.iam.api.Capability} already
 * carries {@code commercial.subscription.manage} and
 * {@code commercial.override.approve}, and because a subscription is one of the
 * four things in here rather than the thing itself: a plan catalogue, a
 * subscription, an entitlement resolution and a usage ledger are not all
 * subscriptions, and the module that owns a meter should not be named after the
 * row the meter is checked against.
 *
 * <p>What is owed, never how it is paid. ADR 0013 owns money movement.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Commercial")
package uz.qoida.platform.commercial;
