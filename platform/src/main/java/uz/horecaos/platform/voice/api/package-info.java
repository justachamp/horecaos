/**
 * What {@code integration} may hold of this module: the inbound port a
 * hosted-PBX or Asterisk-class adapter drives, the read port an adapter (or a
 * future ADR 0059 inbox assignment) uses to see who is online, and the
 * channel-neutral event this module publishes. Nothing here names a provider,
 * a webhook DTO, or an AMI/ARI wire type.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.horecaos.platform.voice.api;
