package uz.horecaos.platform.notifications.application;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction an operations alert is written in when the event that causes it has already committed (ADR 0187).
 *
 * <p>A {@code TransactionPhase.AFTER_COMMIT} listener runs while the publisher's transaction is finished but its
 * resources are still bound to the thread, so anything it writes through the ordinary data access joins that finished
 * transaction and is never committed. The listener has to open one of its own ({@code REQUIRES_NEW}), the way {@code
 * PrintJobPlanner} does for the same reason. This is that, in one place, so a trigger that is told after the commit (a
 * review is news, and must not fail the customer's submission; a till difference is found by a pass that has already written
 * it) neither loses its alert nor repeats the arrangement.
 *
 * <p>A failure inside {@link #run} rolls the alert's own transaction back and is rethrown to the caller, which logs it by
 * identifier and goes no further: the telling is lost, the thing that was told about is not.
 */
@Component
public class AfterCommitAlertTransaction {

    private final @Nullable TransactionTemplate template;

    @Autowired
    public AfterCommitAlertTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate own = new TransactionTemplate(transactionManager);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template = own;
    }

    private AfterCommitAlertTransaction() {
        this.template = null;
    }

    /** No transaction at all: for a unit test whose alert port is a recording double. */
    public static AfterCommitAlertTransaction withoutTransaction() {
        return new AfterCommitAlertTransaction();
    }

    /** Runs {@code work} in a transaction of its own that commits when it returns. */
    public void run(Runnable work) {
        if (template == null) {
            work.run();
            return;
        }
        template.executeWithoutResult(status -> work.run());
    }
}
