package uz.horecaos.platform.ordering.application;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBranchOverrideReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBranchOverrideReasonStore.ReasonRow;

/**
 * Reading and validating against the platform's curated branch-override-reason
 * list (gap map row {@code 1.3}, V0423) — {@link RejectReasonQueryService}'s
 * own two rules, restated for a different curated list: the code must name a
 * reason that exists and is still active, and {@code OTHER} requires a note
 * so overriding the resolver's proposed branch for an unlisted reason still
 * leaves something readable behind.
 */
@Service
public class BranchOverrideReasonQueryService {

    private final JdbcBranchOverrideReasonStore reasons;

    public BranchOverrideReasonQueryService(JdbcBranchOverrideReasonStore reasons) {
        this.reasons = reasons;
    }

    /** The reasons an operator's override dialog may choose from, in display order. */
    public List<ReasonRow> listActive() {
        return reasons.listActive();
    }

    /**
     * Confirms a chosen code may be used to override the proposed branch right now.
     *
     * @throws UnknownBranchOverrideReasonException the code names no reason at all
     * @throws IllegalArgumentException             the reason exists but is archived, or
     *                                               needs a note that was not given
     */
    public ReasonRow validateForDecision(String code, @Nullable String note) {
        ReasonRow reason = reasons.find(code).orElseThrow(() -> new UnknownBranchOverrideReasonException(code));
        if (!reason.active()) {
            throw new IllegalArgumentException("\"%s\" has been retired and can no longer be used".formatted(code));
        }
        if (reason.requiresNote() && (note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "\"%s\" needs a short note — it carries no wording of its own".formatted(code));
        }
        return reason;
    }

    public static class UnknownBranchOverrideReasonException extends RuntimeException {
        public UnknownBranchOverrideReasonException(String code) {
            super("No branch override reason \"%s\"".formatted(code));
        }
    }
}
