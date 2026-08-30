package uz.horecaos.platform.audit.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.audit.api.ApprovalService;

/**
 * Runs the expiry that ADR 0027 always described and nothing ever called.
 *
 * <p>{@code ApprovalService.expireOverdue} shipped with the maker-checker model
 * and had no caller outside its own test. That was invisible while no policy
 * existed to make a request in the first place.
 *
 * <p><strong>It is bookkeeping, and that is the point.</strong> Correctness does
 * not depend on it: every statement that looks for a live request tests
 * {@code expires_at} as well as {@code status}, so an overdue {@code PENDING} row
 * already fails to authorise anything and already fails to block the maker's
 * resubmission — which raises a fresh request under whatever policy governs then.
 * A rule that only holds while a background job is healthy would not be a rule.
 *
 * <p>What the sweep buys is that the queue an approver reads and the row an
 * investigator reads say the same thing as the clock. A request that lapsed a
 * week ago and still reads {@code PENDING} is an open item on somebody's worklist
 * that nobody can act on, and a status column that has to be interpreted against
 * a timestamp is one an operator will eventually read wrong. The console filters
 * lapsed rows itself so it stays truthful between sweeps; this makes the stored
 * status agree.
 *
 * <p>Deliberately not on the request path. Expiring somebody else's stale
 * requests inside a refund would make one caller pay for a bulk update at random.
 */
@Component
@ConditionalOnProperty(name = "horecaos.audit.approval.expiry.enabled",
        havingValue = "true", matchIfMissing = true)
public class ApprovalExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweeper.class);

    private final ApprovalService approvals;

    public ApprovalExpirySweeper(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @Scheduled(
            initialDelayString = "${horecaos.audit.approval.expiry.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.audit.approval.expiry.interval:PT5M}")
    public void sweep() {
        int expired = approvals.expireOverdue();

        if (expired > 0) {
            // A count and nothing else. Naming the requests would put the tenant,
            // the action and the maker of every lapsed approval into a log that
            // has no reader for them (ADR 0029, ADR 0023).
            log.info("ADR 0027: {} approval requests lapsed without a second signature", expired);
        }
    }
}
