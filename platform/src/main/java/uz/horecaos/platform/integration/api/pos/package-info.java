/**
 * The seam between a POS adapter and the ADR 0007 route.
 *
 * <p>Exposed as a named interface for the reason {@code integration.api.payment}
 * is: the Clopos adapter lives in {@code pos.infrastructure.clopos}, because a
 * vendor's wire knowledge belongs with the module that owns the meaning of what
 * is being sent. This package is what lets that module name a call without
 * putting Camel on its classpath.
 */
@org.springframework.modulith.NamedInterface("pos")
package uz.horecaos.platform.integration.api.pos;
