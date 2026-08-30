package uz.horecaos.platform.payments.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalParameters;
import uz.horecaos.platform.payments.api.EntitlementBenefit;
import uz.horecaos.platform.payments.api.EntitlementScope;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.FutureDiscountCommand;
import uz.horecaos.platform.payments.settlement.OrderRemedyService.RefundCommand;

/**
 * What one signature on a remedy is allowed to cover (ADR 0027).
 *
 * <p>Every assertion here is a materially different action that used to share a
 * hash with the one the checker actually read. These are unit tests on purpose:
 * the property is a property of the hash, and putting it behind a container start
 * would mean it is checked less often than the thing it protects changes.
 */
class RemedyApprovalHashTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b01");
    private static final UUID ORDER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120b02");
    private static final ActorRef MAKER = ActorRef.user("operator-1", "Operator One");

    /**
     * The sharpest one, and the reason this lane exists.
     *
     * <p>Five hundred thousand som goes back to a customer. The checker reads
     * "returned through the CLICK cabinet, reference CLICK-88213, by the gateway
     * account". Under the old hash — order, type, amount, reason code — the maker
     * could execute "handed over in cash by me, no reference at all" on the same
     * signature. Both are recorded as attested money the platform did not move,
     * both are unverifiable until a settlement import that does not exist, and
     * only one of them will ever appear in anybody's settlement file.
     */
    @Test
    @DisplayName("one signature does not cover two irreconcilable claims about where the money went")
    void theAttestationIsPartOfWhatWasApproved() {
        RefundCommand approved = refund().channel(ExecutionChannel.PROVIDER_CONSOLE)
                .providerReference("CLICK-88213")
                .executedBy("gateway-account")
                .build();
        RefundCommand executed = refund().channel(ExecutionChannel.CASH_DRAWER)
                .providerReference(null)
                .executedBy("operator-1")
                .build();

        assertThat(OrderRemedyService.refundApprovalHash(executed, RemedyType.ORDER_REFUND))
                .as("cash out of the drawer by me is not the CLICK reversal the checker signed")
                .isNotEqualTo(OrderRemedyService.refundApprovalHash(approved, RemedyType.ORDER_REFUND));
    }

    @Test
    void eachHalfOfTheAttestationBindsOnItsOwn() {
        String base = OrderRemedyService.refundApprovalHash(refund().build(), RemedyType.ORDER_REFUND);

        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().providerReference("CLICK-99999").build(), RemedyType.ORDER_REFUND))
                .as("a different cabinet reference points at a different settlement line")
                .isNotEqualTo(base);
        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().executedBy("somebody-else").build(), RemedyType.ORDER_REFUND))
                .as("who moved the money is the claim being attested")
                .isNotEqualTo(base);
        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().executedAt(Instant.parse("2026-08-24T09:00:00Z"))
                                .build(),
                        RemedyType.ORDER_REFUND))
                .as("when they say they did it decides which settlement day it lands in")
                .isNotEqualTo(base);
    }

    /**
     * A null provider reference and an empty one are different claims: the first
     * says the cabinet gave none, the second says it gave a blank. The hash keeps
     * them apart because a null segment is length -1 rather than length 0.
     */
    @Test
    void anAbsentProviderReferenceIsNotAnEmptyOne() {
        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().providerReference(null).build(), RemedyType.ORDER_REFUND))
                .isNotEqualTo(OrderRemedyService.refundApprovalHash(
                        refund().providerReference("").build(), RemedyType.ORDER_REFUND));
    }

    @Test
    void theEntryPointIsPartOfWhatWasApproved() {
        RefundCommand command = refund().build();

        assertThat(OrderRemedyService.refundApprovalHash(command, RemedyType.ORDER_REFUND))
                .as("a 300 000 refund is not a 300 000 delivery-fee reimbursement")
                .isNotEqualTo(OrderRemedyService.refundApprovalHash(command, RemedyType.DELIVERY_FEE_REIMBURSEMENT));
    }

    @Test
    void theAmountAndTheOrderStillBind() {
        String base = OrderRemedyService.refundApprovalHash(refund().build(), RemedyType.ORDER_REFUND);

        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().amountMinor(900_000L).build(), RemedyType.ORDER_REFUND))
                .isNotEqualTo(base);
        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().orderId(UUID.randomUUID()).build(), RemedyType.ORDER_REFUND))
                .isNotEqualTo(base);
    }

    /**
     * The idempotency key is excluded on purpose, and that decision is load
     * bearing: single use is carried by consuming the approval, not by the hash.
     * A retry of the same submission carries a fresh key and must still be the
     * same intended action, or a maker resuming after approval would never be able
     * to match their own request.
     */
    @Test
    void aRetryOfTheSameSubmissionIsTheSameIntendedAction() {
        assertThat(OrderRemedyService.refundApprovalHash(
                        refund().idempotencyKey("attempt-2")
                                .correlationId("trace-2")
                                .build(),
                        RemedyType.ORDER_REFUND))
                .isEqualTo(OrderRemedyService.refundApprovalHash(refund().build(), RemedyType.ORDER_REFUND));
    }

    /**
     * Two grants that cost the same at the threshold and are not remotely the
     * same grant. The old hash was built over {@code perUse × uses}, so both of
     * these produced 500 000 and both produced the same sixty-four characters.
     */
    @Test
    @DisplayName("a ten-use capped percentage does not ride in on a one-use fixed amount's signature")
    void theFactorsBindNotJustTheirProduct() {
        FutureDiscountCommand signed = new FutureDiscountCommand(
                TENANT,
                ORDER,
                EntitlementScope.SUBTOTAL,
                EntitlementBenefit.FIXED_AMOUNT,
                null,
                500_000L,
                null,
                1,
                Duration.ofDays(7),
                "SERVICE_FAILURE",
                "Cold delivery",
                MAKER,
                "key-1",
                "trace-1");
        FutureDiscountCommand executed = new FutureDiscountCommand(
                TENANT,
                ORDER,
                EntitlementScope.DELIVERY_FEE,
                EntitlementBenefit.PERCENT,
                10_000,
                null,
                50_000L,
                10,
                Duration.ofDays(365),
                "SERVICE_FAILURE",
                "Cold delivery",
                MAKER,
                "key-1",
                "trace-1");

        assertThat(exposure(signed))
                .as("the two are the same exposure, which is why the old hash could not tell them apart")
                .isEqualTo(exposure(executed));
        assertThat(OrderRemedyService.futureDiscountApprovalHash(executed))
                .isNotEqualTo(OrderRemedyService.futureDiscountApprovalHash(signed));
    }

    @Test
    void everyFactorOfAGrantBindsOnItsOwn() {
        FutureDiscountCommand base = discount(
                EntitlementBenefit.FIXED_AMOUNT, 500_000L, null, 1, Duration.ofDays(7), EntitlementScope.SUBTOTAL);
        String hash = OrderRemedyService.futureDiscountApprovalHash(base);

        assertThat(OrderRemedyService.futureDiscountApprovalHash(discount(
                        EntitlementBenefit.FIXED_AMOUNT,
                        500_000L,
                        null,
                        2,
                        Duration.ofDays(7),
                        EntitlementScope.SUBTOTAL)))
                .as("twice as many uses is twice the liability")
                .isNotEqualTo(hash);
        assertThat(OrderRemedyService.futureDiscountApprovalHash(discount(
                        EntitlementBenefit.FIXED_AMOUNT,
                        500_000L,
                        null,
                        1,
                        Duration.ofDays(365),
                        EntitlementScope.SUBTOTAL)))
                .as("a year is not a week; an entitlement's window is what prices it")
                .isNotEqualTo(hash);
        assertThat(OrderRemedyService.futureDiscountApprovalHash(discount(
                        EntitlementBenefit.FIXED_AMOUNT,
                        500_000L,
                        null,
                        1,
                        Duration.ofDays(7),
                        EntitlementScope.DELIVERY_FEE)))
                .as("what the grant applies to changes what it is worth")
                .isNotEqualTo(hash);
    }

    /**
     * The drift guard, stated as a list somebody has to change on purpose.
     *
     * <p>The hash is derived from the record's components, so a field added to
     * {@link RefundCommand} is covered without anyone touching
     * {@code refundApprovalHash} — that is the property that stops this class of
     * defect recurring. This test does not provide that property; it makes the
     * change visible, so an author who adds a component sees the exclusions and
     * confirms which side of the line their field belongs on.
     */
    @Test
    void aComponentAddedToARemedyCommandEntersTheHashAndThisListSaysSo() {
        assertThat(ApprovalParameters.coveredComponents(
                        RefundCommand.class, "actor", "idempotencyKey", "correlationId"))
                .containsExactly(
                        "tenantId",
                        "orderId",
                        "amountMinor",
                        "currency",
                        "reasonCode",
                        "reason",
                        "channel",
                        "providerReference",
                        "executedBy",
                        "executedAt");

        assertThat(ApprovalParameters.coveredComponents(
                        FutureDiscountCommand.class, "actor", "idempotencyKey", "correlationId"))
                .containsExactly(
                        "tenantId",
                        "orderId",
                        "appliesTo",
                        "benefit",
                        "percentBasisPoints",
                        "amountMinor",
                        "maximumMinor",
                        "uses",
                        "validFor",
                        "reasonCode",
                        "reason");
    }

    @Test
    void anExclusionNamingAFieldThatIsNotThereFailsLoudly() {
        assertThatThrownBy(() -> ApprovalParameters.coveredComponents(RefundCommand.class, "idempotency_key"))
                .as("a renamed component must not stay excluded under its old name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    private static long exposure(FutureDiscountCommand command) {
        long perUse =
                command.benefit() == EntitlementBenefit.FIXED_AMOUNT ? command.amountMinor() : command.maximumMinor();
        return perUse * command.uses();
    }

    private static FutureDiscountCommand discount(
            EntitlementBenefit benefit,
            Long amountMinor,
            Long maximumMinor,
            int uses,
            Duration validFor,
            EntitlementScope appliesTo) {
        return new FutureDiscountCommand(
                TENANT,
                ORDER,
                appliesTo,
                benefit,
                null,
                amountMinor,
                maximumMinor,
                uses,
                validFor,
                "SERVICE_FAILURE",
                "Cold delivery",
                MAKER,
                "key-1",
                "trace-1");
    }

    private static Builder refund() {
        return new Builder();
    }

    /** A refund command with one field varied per test, so the varied field is the whole diff. */
    private static final class Builder {

        private UUID orderId = ORDER;
        private long amountMinor = 500_000L;
        private ExecutionChannel channel = ExecutionChannel.PROVIDER_CONSOLE;
        private String providerReference = "CLICK-88213";
        private String executedBy = "gateway-account";
        private Instant executedAt = Instant.parse("2026-08-25T14:02:00Z");
        private String idempotencyKey = "attempt-1";
        private String correlationId = "trace-1";

        Builder orderId(UUID value) {
            this.orderId = value;
            return this;
        }

        Builder amountMinor(long value) {
            this.amountMinor = value;
            return this;
        }

        Builder channel(ExecutionChannel value) {
            this.channel = value;
            return this;
        }

        Builder providerReference(String value) {
            this.providerReference = value;
            return this;
        }

        Builder executedBy(String value) {
            this.executedBy = value;
            return this;
        }

        Builder executedAt(Instant value) {
            this.executedAt = value;
            return this;
        }

        Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        RefundCommand build() {
            return new RefundCommand(
                    TENANT,
                    orderId,
                    amountMinor,
                    "UZS",
                    "SERVICE_FAILURE",
                    "Customer reported a missing item",
                    channel,
                    providerReference,
                    executedBy,
                    executedAt,
                    MAKER,
                    idempotencyKey,
                    correlationId);
        }
    }
}
