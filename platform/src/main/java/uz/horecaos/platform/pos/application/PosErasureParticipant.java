package uz.horecaos.platform.pos.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * Takes the copy of a customer's telephone number out of the POS export records (ADR 0029, ADR 0011).
 *
 * <p>Every order exported to a till leaves one {@code integration.pos_order_exports} row, and the row keeps an unsalted
 * SHA-256 of the order's telephone number so a recovery read can tell whether a candidate order at the till is the one
 * we may have sent. That digest is reversible by trying every number, so it is a copy of the number and an erasure that
 * leaves it has not erased her. This participant replaces the digest on each of her orders' exports with a random value.
 *
 * <p>What stays: the export itself, its state, its attempts and the fingerprint of the lines, which is the evidence that
 * a ticket was sent, and which names no one. The recovery read compares the number of a live order with the till's, never
 * with this column, and an erased order has no number left to compare.
 */
@Component
@ErasesCustomerData(
        name = "pos",
        tables = {"integration.pos_order_exports"})
public class PosErasureParticipant implements CustomerErasureParticipant {

    private final JdbcPosExportStore exports;

    public PosErasureParticipant(JdbcPosExportStore exports) {
        this.exports = exports;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.builder()
                .add(
                        "integration.pos_order_exports",
                        exports.anonymizePhoneHashesOfCustomer(tenantId, customerAccountId))
                .build();
    }
}
