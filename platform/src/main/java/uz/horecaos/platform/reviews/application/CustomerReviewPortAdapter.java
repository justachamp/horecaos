package uz.horecaos.platform.reviews.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.reviews.api.CustomerReviewPort;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@link CustomerReviewPort} over {@link ReviewSubmissionService} (ADR 0075).
 *
 * <p>Adds no rule of its own. Every eligibility decision is made by the service
 * the storefront controller calls, and this class only turns the exception that
 * service raises for a web caller into the outcome a bot can put in a sentence
 * — the same shape {@code OrderDecisionPortAdapter} takes for the staff half.
 *
 * <p>{@code RESOURCE_NOT_FOUND} and {@code UNPROCESSABLE_STATE} collapse into
 * one {@code NOT_ELIGIBLE}, deliberately and following ADR 0071's own choice:
 * that service already answers "not yours" and "does not exist" identically so
 * a customer cannot probe order ids, and distinguishing "yours but not finished"
 * in the bot's reply would hand back exactly the signal it withholds.
 */
@Service
public class CustomerReviewPortAdapter implements CustomerReviewPort {

    private final ReviewSubmissionService reviews;
    private final JdbcReviewStore store;

    public CustomerReviewPortAdapter(ReviewSubmissionService reviews, JdbcReviewStore store) {
        this.reviews = reviews;
        this.store = store;
    }

    @Override
    public Outcome rate(UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating) {
        try {
            reviews.submit(tenantId, brandId, orderId, customerAccountId, rating, null);
            return Outcome.RECORDED;
        } catch (ApiException refused) {
            return refused.errorCode() == ErrorCode.RESOURCE_CONFLICT ? Outcome.ALREADY_RATED : Outcome.NOT_ELIGIBLE;
        }
    }

    @Override
    public boolean hasReview(UUID tenantId, UUID orderId) {
        return store.findByOrder(tenantId, orderId).isPresent();
    }
}
