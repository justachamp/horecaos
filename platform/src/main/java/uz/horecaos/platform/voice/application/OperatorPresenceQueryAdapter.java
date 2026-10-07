package uz.horecaos.platform.voice.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.voice.api.OperatorPresenceQueryPort;
import uz.horecaos.platform.voice.domain.OperatorPresenceState;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;

/**
 * {@link OperatorPresenceQueryPort} over {@code voice.operator_presence}: who has
 * said they are {@code ONLINE} at a branch right now.
 *
 * <p>The port was declared with ADR 0064 and nothing implemented it until ADR
 * 0069 needed it: the assistant tells a customer the truth about whether anyone
 * is there to receive a handoff, and that is exactly this question.
 *
 * <p><strong>What "online" means here, and does not.</strong> Presence is
 * self-declared (an operator marks themselves {@code ONLINE}, {@code PAUSED},
 * {@code WRAP_UP} or {@code OFFLINE}) and carries no heartbeat, so an operator
 * who closed their laptop without going offline still reads as online. That is
 * the model ADR 0064 decided, and this adapter reports it without improving on it
 * -- a paused or wrapping-up operator is not online, since neither is available
 * to take a new conversation.
 */
@Service
public class OperatorPresenceQueryAdapter implements OperatorPresenceQueryPort {

    private final JdbcVoiceStore store;

    public OperatorPresenceQueryAdapter(JdbcVoiceStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OnlineOperator> online(UUID tenantId, UUID locationId) {
        return store.presenceForLocation(tenantId, locationId).stream()
                .filter(row -> OperatorPresenceState.ONLINE.name().equals(row.state()))
                .map(row -> new OnlineOperator(row.operatorPrincipalId(), row.state()))
                .toList();
    }
}
