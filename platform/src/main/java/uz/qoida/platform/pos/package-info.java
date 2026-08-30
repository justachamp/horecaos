/**
 * The point of sale: installations, discovered capabilities, catalog
 * synchronization, and order export (ADR 0011, ADR 0012).
 *
 * <p>A module of its own rather than a corner of {@code integration}, and the
 * boundary follows what each side owns. {@code integration} owns the generic
 * ADR 0026 provider model — an installation, a binding, a secret reference, an
 * entity mapping — and the ADR 0007 route policy that gets bytes to a provider
 * and classifies what came back. This module owns what a <em>till</em> means:
 * that a capability is a property of one restaurant's credential rather than of
 * the vendor, that a menu arriving from a provider is a proposal and not the
 * menu, and that an order whose export outcome is unknown is a durable state
 * somebody has to settle rather than a message to send again.
 *
 * <p>Nothing here imports Camel. The provider adapter names calls;
 * {@code integration.camel.pos} turns them into exchanges, and
 * {@code PosModuleBoundaryTests} keeps that from eroding — a route deciding
 * whether an export landed is exactly the coupling ADR 0007 exists to prevent.
 *
 * <p>Nothing here branches on a provider name outside
 * {@code pos.infrastructure.clopos}. Domain code asks whether a binding has a
 * capability; it never asks whether the binding is Clopos.
 */
@org.springframework.modulith.ApplicationModule(displayName = "POS")
package uz.qoida.platform.pos;
