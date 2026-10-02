/**
 * What the marketplace availability reconciler asks of a provider adapter (ADR 0141,
 * ADR 0040's {@code marketplace.availability.push}): one state-set call per mapped item,
 * and the three conclusions the route draws about what the partner now holds.
 */
@org.springframework.modulith.NamedInterface("marketplace")
package uz.horecaos.platform.integration.api.marketplace;
