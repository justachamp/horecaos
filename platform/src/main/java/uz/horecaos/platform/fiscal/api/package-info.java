/**
 * What the fiscal module exposes, and the one thing it needs somebody else to
 * implement (ADR 0038).
 *
 * <p>No fiscal sign, no receipt URL and no marking code appears here. Those are
 * ADR 0029 protected evidence and ADR 0032 keeps them off events; a consumer that
 * needs them reads them through an authorized API with a recorded purpose, and a
 * consumer that thinks it needs a marking code is asking the wrong module.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.fiscal.api;
