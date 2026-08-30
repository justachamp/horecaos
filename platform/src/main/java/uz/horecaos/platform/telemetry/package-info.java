/**
 * Real-time operational push, and field telemetry from the in-house courier
 * fleet (ADR 0045).
 *
 * <p>Two halves that share nothing but this ADR. The push half is the transport
 * that stops an operations console going stale: Server-Sent Events carrying a
 * signal rather than state, fanned out across replicas on a {@code
 * realtime.signals} Kafka topic, with a polling fallback that must keep working.
 * The telemetry half is courier location — ingested for dispatch and dispute
 * evidence, never published to a customer, collected only inside a duty session,
 * and dropped with its daily partition after the derived retention window.
 *
 * <p>The two are deliberately separable. Turning the stream flag off returns
 * every operational surface to polling with no code change, and closing every
 * duty session stops collection at the source rather than at a screen.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Telemetry")
package uz.horecaos.platform.telemetry;
