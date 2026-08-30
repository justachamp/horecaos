package uz.qoida.platform.courier.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.ApprovalParameters;
import uz.qoida.platform.courier.application.CourierAdjustmentService.AdjustmentCommand;
import uz.qoida.platform.courier.domain.AdjustmentOrigin;
import uz.qoida.platform.courier.domain.PayoutMethod;
import uz.qoida.platform.courier.domain.SettlementPeriodStatus;
import uz.qoida.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;

/**
 * What one signature on a courier adjustment or payout is allowed to cover
 * (ADR 0027, ADR 0042).
 */
class CourierApprovalHashTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c01");
    private static final UUID COURIER = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c02");
    private static final UUID CHILONZOR = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c03");
    private static final UUID YUNUSOBOD = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c04");
    private static final UUID PERIOD = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c05");
    private static final UUID ENGAGEMENT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120c06");
    private static final ActorRef MAKER = ActorRef.user("manager-1", "Manager One");

    /**
     * The location decides whose P&amp;L bears the penalty, and it was outside the
     * hash. A checker approving a 200 000 debit against a courier saw one branch
     * carry it; the maker could post the identical debit against another, and the
     * two requests were indistinguishable on the console.
     */
    @Test
    @DisplayName("a penalty approved against one branch cannot be charged to another")
    void theLocationIsPartOfWhatWasApproved() {
        assertThat(CourierAdjustmentService.parametersHash(penalty(YUNUSOBOD)))
                .isNotEqualTo(CourierAdjustmentService.parametersHash(penalty(CHILONZOR)));
    }

    @Test
    void theAmountAndTheOriginStillBind() {
        String base = CourierAdjustmentService.parametersHash(penalty(CHILONZOR));

        assertThat(CourierAdjustmentService.parametersHash(
                new AdjustmentCommand(TENANT, COURIER, CHILONZOR, -500_000L, "UZS",
                        "UNDELIVERED_ORDER", AdjustmentOrigin.MANUAL, "key-1", MAKER,
                        "Order never arrived", "trace-1")))
                .as("a fifty-thousand penalty's signature is not a five-hundred-thousand one's")
                .isNotEqualTo(base);
        assertThat(CourierAdjustmentService.parametersHash(
                new AdjustmentCommand(TENANT, COURIER, CHILONZOR, -200_000L, "UZS",
                        "UNDELIVERED_ORDER", AdjustmentOrigin.RULE, "key-1", MAKER,
                        "Order never arrived", "trace-1")))
                .as("a rule-derived penalty is reproducible and a manual one is somebody's judgement")
                .isNotEqualTo(base);
    }

    @Test
    void aRetryOfTheSameSubmissionIsTheSameIntendedAction() {
        assertThat(CourierAdjustmentService.parametersHash(
                new AdjustmentCommand(TENANT, COURIER, CHILONZOR, -200_000L, "UZS",
                        "UNDELIVERED_ORDER", AdjustmentOrigin.MANUAL, "key-9", MAKER,
                        "Order never arrived", "trace-9")))
                .isEqualTo(CourierAdjustmentService.parametersHash(penalty(CHILONZOR)));
    }

    /**
     * The payout hash was {@code sha256(periodId + ":" + amountPayableMinor)}.
     * {@link PayoutMethod} is the rail the money leaves on and it is written onto
     * the payout row: an accountant asked to see a flagged period's exposure
     * before a bank transfer signed for a bank transfer, and the maker could
     * authorise the identical sum as cash handed over at a branch.
     */
    @Test
    @DisplayName("a payout approved as a bank transfer cannot be authorised as cash at a branch")
    void thePayoutMethodIsPartOfWhatWasApproved() {
        PeriodRow period = closedPeriod(1_200_000L);

        assertThat(CourierSettlementService.payoutApprovalHash(TENANT, period,
                PayoutMethod.CASH_AT_BRANCH))
                .isNotEqualTo(CourierSettlementService.payoutApprovalHash(TENANT, period,
                        PayoutMethod.BANK_TRANSFER));
    }

    @Test
    void thePayoutHashNamesItsTenantAndItsCourier() {
        PeriodRow period = closedPeriod(1_200_000L);
        String base = CourierSettlementService.payoutApprovalHash(TENANT, period,
                PayoutMethod.BANK_TRANSFER);

        assertThat(CourierSettlementService.payoutApprovalHash(UUID.randomUUID(), period,
                PayoutMethod.BANK_TRANSFER))
                .as("a hash that does not name the tenant is one column away from a cross-tenant match")
                .isNotEqualTo(base);
        assertThat(CourierSettlementService.payoutApprovalHash(TENANT,
                closedPeriod(1_200_000L, UUID.randomUUID()), PayoutMethod.BANK_TRANSFER))
                .as("who is being paid is part of what was approved")
                .isNotEqualTo(base);
        assertThat(CourierSettlementService.payoutApprovalHash(TENANT, closedPeriod(1_300_000L),
                PayoutMethod.BANK_TRANSFER))
                .isNotEqualTo(base);
    }

    /**
     * The drift guard. Adding a component to {@link AdjustmentCommand} puts it in
     * the hash by itself; this makes the change visible so the author confirms
     * which side of the line it belongs on.
     */
    @Test
    void aComponentAddedToAnAdjustmentEntersTheHashAndThisListSaysSo() {
        assertThat(ApprovalParameters.coveredComponents(AdjustmentCommand.class,
                "idempotencyKey", "actor", "correlationId"))
                .containsExactly("tenantId", "courierId", "locationId", "amountMinor", "currency",
                        "reasonCode", "origin", "reason");
    }

    private static AdjustmentCommand penalty(UUID locationId) {
        return new AdjustmentCommand(TENANT, COURIER, locationId, -200_000L, "UZS",
                "UNDELIVERED_ORDER", AdjustmentOrigin.MANUAL, "key-1", MAKER,
                "Order never arrived", "trace-1");
    }

    private static PeriodRow closedPeriod(long amountPayableMinor) {
        return closedPeriod(amountPayableMinor, COURIER);
    }

    private static PeriodRow closedPeriod(long amountPayableMinor, UUID courierId) {
        return new PeriodRow(PERIOD, TENANT, courierId, ENGAGEMENT,
                LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-15"),
                SettlementPeriodStatus.CLOSED, "UZS", amountPayableMinor, 0L, 0L,
                amountPayableMinor, 40, 38, 120_000L, 86_400L, 12, true, "a".repeat(64),
                "manager-1", Instant.parse("2026-08-16T09:00:00Z"), null, 1);
    }
}
