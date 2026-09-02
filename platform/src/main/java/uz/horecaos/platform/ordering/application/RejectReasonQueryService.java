package uz.horecaos.platform.ordering.application;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcRejectReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcRejectReasonStore.ReasonRow;

/**
 * Reading and validating against the platform's curated reject-reason list
 * (wave 24, V0119).
 *
 * <p>Two rules live here, and every reject path — the operations board, the
 * Telegram bot — goes through them rather than re-implementing either:
 *
 * <p>The code must name a reason that exists and is still active. An unknown
 * code is a client bug (a stale build, a typo); an inactive one is a reason
 * that used to be offered and no longer is — nothing about "inactive" is
 * knowable from the code string itself, which is why this is a lookup and
 * not a format check.
 *
 * <p>{@code OTHER} keeps the information free text used to carry: picking it
 * without a note would silently regress the exact problem this registry
 * exists to fix, so it is refused rather than accepted with nothing to show
 * for it. {@code requiresNote} is a column on the reason, not a hardcoded
 * check against one literal code, so a future platform reason can carry the
 * same rule without a second special case here.
 */
@Service
public class RejectReasonQueryService {

    private final JdbcRejectReasonStore reasons;

    public RejectReasonQueryService(JdbcRejectReasonStore reasons) {
        this.reasons = reasons;
    }

    /** The reasons an operator (or the bot's picker) may choose from, in display order. */
    public List<ReasonRow> listActive() {
        return reasons.listActive();
    }

    /**
     * Confirms a chosen code may be used to reject an order right now.
     *
     * @throws UnknownRejectReasonException the code names no reason at all
     * @throws IllegalArgumentException     the reason exists but is archived, or
     *                                      needs a note that was not given
     */
    public ReasonRow validateForDecision(String code, @Nullable String note) {
        ReasonRow reason = reasons.find(code).orElseThrow(() -> new UnknownRejectReasonException(code));
        if (!reason.active()) {
            throw new IllegalArgumentException("\"%s\" has been retired and can no longer be used".formatted(code));
        }
        if (reason.requiresNote() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "\"%s\" needs a short note — it carries no wording of its own".formatted(code));
        }
        return reason;
    }

    public static class UnknownRejectReasonException extends RuntimeException {
        public UnknownRejectReasonException(String code) {
            super("No reject reason \"%s\"".formatted(code));
        }
    }
}
