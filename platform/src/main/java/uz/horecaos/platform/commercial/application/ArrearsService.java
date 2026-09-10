package uz.horecaos.platform.commercial.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.commercial.api.ArrearsDirectory;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcArrearsStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcArrearsStore.ArrearRow;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;

/**
 * Tenants in arrears (ADR 0089): who is past due or suspended, since when,
 * and the last statement each was issued.
 *
 * <p>Moving a tenant between stages stays the subscription transition it
 * always was, with its reason and expected version. Nothing here moves a
 * tenant by itself: ADR 0021 treats lateness as a conversation, so the
 * platform's part is to say when one is due.
 */
@Service
public class ArrearsService implements ArrearsDirectory {

    private final JdbcArrearsStore arrears;
    private final JdbcStatementStore statements;

    public ArrearsService(JdbcArrearsStore arrears, JdbcStatementStore statements) {
        this.arrears = arrears;
        this.statements = statements;
    }

    @Transactional(readOnly = true)
    public Board board() {
        List<ArrearRow> rows = arrears.board();
        Map<UUID, Statement> latest = statements.latestIssued(
                rows.stream().map(ArrearRow::tenantId).distinct().toList());
        return new Board(rows, latest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Arrear> pastDueSince(Instant before, int limit) {
        return arrears.pastDueSince(before, limit);
    }

    /** The board's rows, and the newest standing statement of each tenant on it. */
    public record Board(List<ArrearRow> rows, Map<UUID, Statement> latestStatements) {}
}
