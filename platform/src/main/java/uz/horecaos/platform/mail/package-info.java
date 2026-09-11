/**
 * The platform's own email (ADR 0097): one SMTP connection, used by nothing
 * but this module, sending what the platform itself has to say -- an owner's
 * invitation first. A tenant's email to its own customers is ADR 0020's.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform email")
package uz.horecaos.platform.mail;
