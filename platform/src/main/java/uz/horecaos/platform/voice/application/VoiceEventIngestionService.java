package uz.horecaos.platform.voice.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.voice.api.VoiceCallEventRecorded;
import uz.horecaos.platform.voice.api.VoiceEventInboundPort;
import uz.horecaos.platform.voice.domain.CallerNumberDisplay;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.NewCallEvent;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.PresenceRow;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.RosterEntry;

/**
 * The one door a provider adapter uses to hand this module a canonical call
 * event (ADR 0064). Both adapter kinds call this identical method; nothing
 * here knows which one it was.
 */
@Service
public class VoiceEventIngestionService implements VoiceEventInboundPort {

    private static final String CALL_EVENTS_TABLE = "voice.call_events";
    private static final String CALLER_NUMBER_COLUMN = "caller_number_encrypted";

    private final JdbcVoiceStore store;
    private final CustomerPhoneLookup customers;
    private final FieldProtection protection;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public VoiceEventIngestionService(
            JdbcVoiceStore store,
            CustomerPhoneLookup customers,
            FieldProtection protection,
            ApplicationEventPublisher events,
            Clock clock) {
        this.store = store;
        this.customers = customers;
        this.protection = protection;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IngestOutcome ingest(InboundCallEvent event) {
        UUID callEventId = UUID.randomUUID();
        Instant now = clock.instant();

        boolean isOfferedOrMissed =
                event.type() == CallEventTypeCode.OFFERED || event.type() == CallEventTypeCode.MISSED;
        List<RosterEntry> roster = isOfferedOrMissed ? rosterSnapshot(event.tenantId(), event.locationId()) : List.of();

        UUID resolvedCustomerAccountId = null;
        String callerNumberEncrypted = null;
        String callerNumberMasked = null;
        if (event.type() == CallEventTypeCode.OFFERED && event.callerNumberRaw() != null) {
            String rawNumber = event.callerNumberRaw();
            callerNumberEncrypted = encryptCallerNumber(event.tenantId(), callEventId, rawNumber);
            // Computed directly from the plaintext this method already holds,
            // never by decrypting callerNumberEncrypted back — see the mask
            // column's own migration comment for why a decrypt-per-poll would
            // be wrong.
            callerNumberMasked = CallerNumberDisplay.mask(rawNumber);
            resolvedCustomerAccountId = resolveCustomer(event.tenantId(), rawNumber);
        }

        Integer durationSeconds = null;
        if (event.type() == CallEventTypeCode.ENDED) {
            durationSeconds = store.earliestEventAt(event.tenantId(), event.installationId(), event.providerCallId())
                    .map(startedAt -> (int)
                            Duration.between(startedAt, event.occurredAt()).toSeconds())
                    .map(seconds -> Math.max(seconds, 0))
                    .orElse(null);
        }

        // Neither built adapter has an extension-to-operator directory, so a
        // provider that does not itself report who answered leaves this null.
        // The one place this build can still attribute a call honestly is the
        // operator who claimed its screen-pop card through our own app.
        String operatorPrincipalId = event.operatorPrincipalId();
        if (operatorPrincipalId == null
                && (event.type() == CallEventTypeCode.ANSWERED || event.type() == CallEventTypeCode.ENDED)) {
            operatorPrincipalId = store.acknowledgedOperator(
                            event.tenantId(), event.installationId(), event.providerCallId())
                    .orElse(null);
        }

        store.insertCallEvent(new NewCallEvent(
                callEventId,
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                event.installationId(),
                event.bindingId(),
                event.providerCallId(),
                event.type().name(),
                event.direction().name(),
                event.lineDid(),
                callerNumberEncrypted,
                callerNumberMasked,
                resolvedCustomerAccountId,
                operatorPrincipalId,
                durationSeconds,
                event.transferTargetLine(),
                roster,
                event.occurredAt()));

        if (event.type() == CallEventTypeCode.OFFERED) {
            store.openScreenPop(event.tenantId(), event.locationId(), callEventId);
        } else if (event.type() == CallEventTypeCode.ANSWERED
                || event.type() == CallEventTypeCode.ENDED
                || event.type() == CallEventTypeCode.MISSED) {
            // Answered (by anyone), ended, or missed: the ringing card this
            // provider_call_id raised is no longer live, so every operator
            // polling the branch should stop seeing it.
            store.clearScreenPopForCall(event.tenantId(), event.installationId(), event.providerCallId(), now);
        }

        events.publishEvent(new VoiceCallEventRecorded(
                UUID.randomUUID(),
                event.tenantId(),
                callEventId,
                event.installationId(),
                event.providerCallId(),
                event.occurredAt(),
                event.brandId(),
                event.locationId(),
                event.type().name(),
                event.direction().name(),
                event.lineDid(),
                resolvedCustomerAccountId,
                operatorPrincipalId,
                durationSeconds));

        return new IngestOutcome(callEventId);
    }

    private List<RosterEntry> rosterSnapshot(UUID tenantId, UUID locationId) {
        return store.presenceForLocation(tenantId, locationId).stream()
                .map(VoiceEventIngestionService::toRosterEntry)
                .toList();
    }

    private static RosterEntry toRosterEntry(PresenceRow row) {
        return new RosterEntry(row.operatorPrincipalId(), row.state());
    }

    private String encryptCallerNumber(UUID tenantId, UUID callEventId, String rawNumber) {
        return protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef(CALL_EVENTS_TABLE, CALLER_NUMBER_COLUMN, callEventId),
                        rawNumber)
                .serialize();
    }

    /**
     * Best effort: a caller number that does not parse as a phone number at
     * all is an unresolved caller, not an ingestion failure. A ringing phone
     * does not wait for a lookup to be perfect.
     */
    private @Nullable UUID resolveCustomer(UUID tenantId, String rawNumber) {
        try {
            List<CustomerAccountRef> matches = customers.findByPhone(tenantId, rawNumber);
            return matches.isEmpty() ? null : matches.get(0).accountId();
        } catch (RuntimeException unresolvable) {
            return null;
        }
    }
}
