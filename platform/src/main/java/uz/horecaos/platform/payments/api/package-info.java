/**
 * What payments exposes to other modules (ADR 0013).
 *
 * <p>Identifiers, states and totals. No provider payload, no merchant account
 * reference, and no secret reference ever appears here: a consumer that needs more
 * calls an authorized API, and a consumer that thinks it needs a credential is
 * asking the wrong module.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.payments.api;
