/**
 * What marketing exposes: the port it needs the ADR 0020 delivery path to
 * implement, and the shapes that cross it.
 *
 * <p>Nothing that crosses this boundary carries a phone number, an email address,
 * a push token, or a chat id. Marketing addresses a customer by account id and
 * nothing else, because ADR 0020 owns the endpoint and a second copy would double
 * the blast radius of a key compromise for a fact this module never needs.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.marketing.api;
