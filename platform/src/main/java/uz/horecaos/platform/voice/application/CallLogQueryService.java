package uz.horecaos.platform.voice.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore;
import uz.horecaos.platform.voice.infrastructure.persistence.JdbcVoiceStore.CallLogRow;

/** The branch's recent call list (frontend information architecture Sec 1.6). */
@Service
public class CallLogQueryService {

    private static final int DEFAULT_LIMIT = 50;

    private final JdbcVoiceStore store;

    public CallLogQueryService(JdbcVoiceStore store) {
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<CallLogRow> recent(UUID tenantId, UUID locationId) {
        return store.recentCalls(tenantId, locationId, DEFAULT_LIMIT);
    }
}
