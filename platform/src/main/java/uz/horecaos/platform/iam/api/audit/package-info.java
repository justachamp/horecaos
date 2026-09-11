/**
 * The evidence {@code iam} itself has to record (ADR 0098), as a port another
 * module provides.
 *
 * <p>Inverted for the same reason {@code iam.api.mail} is, and a harder one.
 * The {@code audit} module already depends on {@code iam}: {@code AuditFact}
 * carries an {@code iam.api.ResourceScope}, and its approval services are built
 * on {@code iam}'s {@code AuthorizationService} and {@code CurrentActor}. So
 * {@code iam} calling {@code AuditRecorder} directly closes a cycle Spring
 * Modulith refuses — which is exactly what the first version of ADR 0098's
 * password reset did, and what {@code ModularArchitectureTests} caught.
 *
 * <p>{@code iam} therefore says what it needs recorded and {@code audit}
 * implements it, which is the direction the two modules already run in. The
 * port is deliberately narrow: one fact shape, for the security events a staff
 * account's own lifecycle produces, rather than a second general-purpose
 * recorder.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and the adapter could not reference them.
 */
@org.springframework.modulith.NamedInterface("audit")
package uz.horecaos.platform.iam.api.audit;
