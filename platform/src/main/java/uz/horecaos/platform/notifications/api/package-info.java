/**
 * What notifications exposes: the transport port an ADR 0007 route implements,
 * and the shapes that cross it.
 *
 * <p>The rendered message crosses this boundary and is never persisted on either
 * side of it (ADR 0020, ADR 0029).
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.notifications.api;
