/**
 * The one email {@code iam} itself has to send (ADR 0098), as a port another
 * module provides.
 *
 * <p>Inverted on purpose, and this is the whole reason the package exists. The
 * {@code mail} module reads its SMTP password through {@code iam}'s secret
 * manager (ADR 0028), so {@code mail} already depends on {@code iam}; a
 * password-reset relay in {@code iam} calling {@code PlatformMailer} directly
 * would close that into a module cycle Spring Modulith refuses. So {@code iam}
 * declares what it needs to send and {@code mail} implements it, which is the
 * direction the two modules already run in.
 *
 * <p>Needs its own named interface: a {@code @NamedInterface} on a parent
 * package does not cover its sub-packages, so without this the types here are
 * internal to {@code iam} and the adapter could not reference them.
 */
@org.springframework.modulith.NamedInterface("mail")
package uz.horecaos.platform.iam.api.mail;
