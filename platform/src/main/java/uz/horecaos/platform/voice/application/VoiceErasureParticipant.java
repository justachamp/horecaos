package uz.horecaos.platform.voice.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;

/**
 * Erases the number an erased customer called from (ADR 0029, ADR 0064).
 *
 * <p>A call event keeps the caller's number encrypted, for the screen-pop, and a masked form for the call list. For the
 * calls the platform resolved to her account both are overwritten and the link to the account is cut; the event, its
 * type, direction, line, duration and time stay, because that a call was answered is the branch's record. A call from a
 * number nobody resolved to an account is not reachable from an account and is not touched.
 */
@Component
@ErasesCustomerData(
        name = "voice",
        tables = {"voice.call_events"})
public class VoiceErasureParticipant implements CustomerErasureParticipant {

    private static final String TOMBSTONE = "ERASED";

    private final JdbcVoiceStore voice;
    private final FieldProtection protection;

    public VoiceErasureParticipant(JdbcVoiceStore voice, FieldProtection protection) {
        this.voice = voice;
        this.protection = protection;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        int calls = voice.eraseCallerNumbersOf(
                tenantId,
                customerAccountId,
                eventId -> protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef("voice.call_events", "caller_number_encrypted", eventId),
                                TOMBSTONE)
                        .serialize());
        return ErasureCounts.of("voice.call_events", calls);
    }
}
