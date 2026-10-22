package uz.horecaos.platform.ordering.application;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.ordering.api.RedemptionOfferPort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;

/**
 * What the customer on the phone could spend of their points on the order the operator is taking, and what they
 * would still owe in money (ADR 0046, ADR 0185, ADR 0039): the operator's twin of {@link CartRedemptionOffers}.
 *
 * <p>The basket is priced by the very path {@link OperatorOrderQuoteService} prices it by and the pricing is then
 * undone, for the reason that class gives: the figure the operator reads back to the customer must be the figure
 * the order is booked at, and a second preview-only pricing path is the thing to avoid. The offer is then made
 * from that quote's total, delivery fee and promotion flag, by the same port the storefront's offer uses, so a
 * customer is told the same maximum on either side of the same counter.
 *
 * <p>Nothing survives and nothing is held: no cart, no quote, no redemption, no audit fact. The points are
 * debited inside the checkout transaction that places the order, and only if the operator confirms the customer
 * agreed (ADR 0039's attestation).
 */
@Service
public class OperatorRedemptionOffers {

    private final OperatorOrderingService ordering;
    private final TransactionTemplate transactions;
    private final RedemptionOfferPort offers;

    public OperatorRedemptionOffers(
            OperatorOrderingService ordering, TransactionTemplate transactions, RedemptionOfferPort offers) {
        this.ordering = ordering;
        // Its own transaction, never joining a caller's: the rollback below is this method's whole job and must
        // not be able to mark somebody else's work rollback-only.
        TransactionTemplate isolated = new TransactionTemplate(Objects.requireNonNull(
                transactions.getTransactionManager(), "a transaction template always carries its manager"));
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions = isolated;
        this.offers = offers;
    }

    /**
     * Prices the basket as {@link OperatorOrderingService#place} would, keeps nothing, and offers.
     *
     * @throws uz.horecaos.platform.web.api.ApiException {@code VALIDATION_FAILED} for a request that is malformed in
     *         the way {@code place} refuses before it opens a cart
     * @throws CartService.CartRefusedException for any rule the cart refuses on
     */
    public OperatorOffer offer(OperatorOrderingService.PlaceOrderCommand command) {
        ordering.requireWellFormed(command);
        OperatorOffer offer = transactions.execute(status -> {
            try {
                QuoteSnapshot quote = ordering.fillAndPrice(command).priced().quote();
                RedemptionOfferPort.Offer made = offers.offer(
                        command.tenantId(),
                        command.brandId(),
                        command.customerAccountId(),
                        quote.totalMinor(),
                        quote.feeMinor(),
                        quote.currency(),
                        command.channelCode());
                if (!quote.loyaltyRedemptionAllowed()) {
                    made = made.refusedFor(CartRedemptionOffers.REDEMPTION_BLOCKED_BY_PROMOTION);
                }
                return new OperatorOffer(quote.currency(), quote.totalMinor(), quote.feeMinor(), made);
            } finally {
                // On every exit, a refusal included. A cart that was priced is a cart nobody asked to keep.
                status.setRollbackOnly();
            }
        });
        return Objects.requireNonNull(offer, "a transaction callback that returned an offer returns it");
    }

    /** The priced basket's figures and the offer made against them. */
    public record OperatorOffer(
            String currency, long orderTotalMinor, long deliveryFeeMinor, RedemptionOfferPort.Offer offer) {}
}
