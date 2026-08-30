/**
 * What other modules may know about a point of sale (ADR 0011).
 *
 * <p>Deliberately small: a capability vocabulary, what one installation was
 * observed to support, and the port ordering calls to hand an order over. There
 * is no provider name here, no endpoint, no DTO and no credential, because
 * anything a domain module can name is something it can come to depend on.
 *
 * <p>{@code CapabilitySnapshot} is the type worth reading. Its
 * {@code IdempotencyBehaviour} is why the export path looks the way it does:
 * a provider that answers {@code NONE} cannot have its failed commands repeated
 * by any amount of infrastructure, and pretending otherwise turns a lost response
 * into a second dinner.
 */
@org.springframework.modulith.NamedInterface("pos")
package uz.qoida.platform.pos.api;
