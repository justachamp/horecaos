package uz.horecaos.platform.ordering.application;

import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;

/**
 * The one thing ordering asks of ADR 0015's address model (ADR 0019, ADR 0029).
 *
 * <p>A port ordering owns, with its adapter in {@code ordering.infrastructure},
 * for the same reason {@link OrderCatalogSnapshot} is one: ordering needs a few
 * facts from another module's tables and must not acquire a Java dependency on
 * that module's application layer to get them. What crosses this line is one
 * address, for one account, revealed once, with a purpose recorded against it.
 *
 * <p>There is deliberately no method that lists addresses. The storefront already
 * has ADR 0015's own endpoint for that, and a second listing path in ordering
 * would be a second place that decides what a customer may see of their own
 * profile.
 */
public interface CustomerAddressBook {

    /**
     * One active saved address, decrypted, as a destination.
     *
     * <p>The account is a predicate inside the query and never a check after the
     * fact. An address id is a UUID a client supplies, and the whole reason this
     * method takes the account the cart belongs to is that matching on the id
     * alone would let one customer deliver an order to another customer's home —
     * or, more quietly, learn that a given address id is real.
     *
     * @param purpose recorded as an ADR 0027 fact by the reveal. "One customer
     *                choosing where their dinner goes" and "an export of every
     *                address a tenant holds" are the same decrypt to a key and
     *                must not be the same line in an audit log
     * @return empty when the address is not this account's, is archived, or does
     *         not exist. All three are one answer to a caller, because telling
     *         them apart is how an id becomes probeable
     */
    Optional<SavedDestination> destination(UUID tenantId, UUID customerAccountId, UUID addressId, String purpose);

    /**
     * A revealed address, with the label that names it and nothing else.
     *
     * @param destination          null when the address carries no coordinate.
     *                             ADR 0015 makes that an ordinary, finished state
     *                             for a mahalla house given by its ориентир, and
     *                             a {@code DeliveryDestination} cannot exist
     *                             without a point. Refusing it is the caller's
     *                             job; inventing a point for it is nobody's
     * @param deliveryInstructions the customer's standing note for a courier —
     *                             "ring the top bell". Personal data, carried
     *                             separately because it is stored in its own
     *                             column and re-encrypted into its own column
     */
    record SavedDestination(
            UUID addressId, String label, DeliveryDestination destination, String deliveryInstructions) {

        /** Whether this address can be routed to at all. */
        public boolean located() {
            return destination != null;
        }

        /** Names the address and nothing about where it is. */
        @Override
        public String toString() {
            return "SavedDestination[address=%s, located=%s]".formatted(addressId, located());
        }
    }
}
