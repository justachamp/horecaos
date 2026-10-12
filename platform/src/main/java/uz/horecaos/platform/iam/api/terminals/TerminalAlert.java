package uz.horecaos.platform.iam.api.terminals;

import java.time.Instant;
import java.util.UUID;

/**
 * An ADR 0058 operations alert raised by the terminals (ADR 0155): a PIN lock, or a {@code PERSON_REQUIRED} device
 * unseen during service. Published by {@code iam}; {@code notifications} listens and fans it out to the chats that
 * subscribed to the event class, the same shape every other module's operations alert takes.
 *
 * <p>Names a non-personal {@code staffReference} ({@code S-0142}) and a device, never a person: the alert goes to a
 * Telegram group.
 *
 * @param eventClass          {@code TERMINAL_CREDENTIAL_LOCKED} or {@code TERMINAL_DEVICE_UNSEEN}: a {@code
 *                            TelegramEventClass} and its own semantic template key
 * @param subjectId           what the episode is about: the staff member for a lock, the device for an unseen screen
 * @param episode             names the episode for de-duplication: the lock's {@code locked_at}, or the device's last
 *                            seen instant, so a replayed trigger reaches a chat once and a later episode alerts again
 * @param rule                which lock fired (empty for an unseen device)
 * @param staffReference      the non-personal member reference; empty for an unseen device
 * @param deviceName          the device's display name, a label the tenant chose
 */
public record TerminalAlert(
        String eventClass,
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID subjectId,
        String subjectType,
        UUID deviceId,
        String deviceName,
        String staffReference,
        String rule,
        Instant episode,
        UUID eventId) {

    public static final String CREDENTIAL_LOCKED = "TERMINAL_CREDENTIAL_LOCKED";
    public static final String DEVICE_UNSEEN = "TERMINAL_DEVICE_UNSEEN";
}
