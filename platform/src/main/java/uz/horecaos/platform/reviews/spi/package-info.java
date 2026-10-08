/**
 * What reviews needs from somebody else: the seam a service-recovery case reads the compensation an
 * operator linked to it through (ADR 0159, Decision 9).
 *
 * <p>The direction is the reason this package exists. A call from {@code reviews} into {@code payments}
 * would close {@code reviews -> payments -> notifications -> reviews} and {@code reviews -> payments ->
 * integration -> reviews}, so the edge is inverted: {@code reviews} declares the port here and
 * {@code payments} implements it, the direction {@code dinein} already takes for {@code
 * customers.spi.CustomerErasureParticipant}. {@code reviews} collects the implementations by type and
 * imports nothing from {@code payments}.
 */
@org.springframework.modulith.NamedInterface("spi")
package uz.horecaos.platform.reviews.spi;
