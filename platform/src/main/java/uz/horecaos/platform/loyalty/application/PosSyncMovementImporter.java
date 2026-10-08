package uz.horecaos.platform.loyalty.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.loyalty.api.LoyaltySyncDiscrepancyOpened;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.MovementKind;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TillMovement;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.TillMovementCommand;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.TillMovementResult;
import uz.horecaos.platform.loyalty.domain.AccountStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;

/**
 * Imports one movement the till reported, atomically (ADR 0171 Decision 4 and 5).
 *
 * <p>Its own bean, and one transaction per movement, for a reason that is about crashes and not about style: the
 * record of having seen a movement, the ledger entry it became, and the record of what became of it must commit
 * together. Recording first and applying after, in separate commits, would leave a movement known and never applied
 * if the process died between them -- and because a known movement is skipped on replay, it would stay unapplied for
 * ever. Nothing in here calls the till: the page was fetched before this method was entered.
 *
 * <p><strong>Idempotent three ways.</strong> The movement's own id is unique per binding ({@code
 * uq_pos_sync_movement_external}), the ledger entry carries the key {@code POSSYNC:<bindingId>:<movementId>}, and
 * a replayed page therefore imports each movement once however many times the till repeats it.
 *
 * <p><strong>Caps hold what a person has not seen.</strong> A credit that would take a customer above the per-customer
 * cap for the business day, or the binding above its own, is stored {@code HELD}, credits nothing, and opens a
 * discrepancy. A spend larger than the balance applies what the balance covers and records the rest as absorbed by
 * the brand; the balance never goes negative (ADR 0046).
 */
@Service
public class PosSyncMovementImporter {

    public enum Outcome {
        APPLIED,
        HELD,
        PARTIALLY_APPLIED,
        REJECTED,
        /** The movement was already known; nothing was written. */
        DUPLICATE
    }

    public record Imported(
            Outcome outcome,
            long appliedMinor,
            long absorbedMinor,
            @Nullable UUID discrepancyId) {}

    private final JdbcPosBalanceSyncStore store;
    private final JdbcLoyaltyStore loyalty;
    private final LoyaltyAdjustmentService adjustments;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PosSyncMovementImporter(
            JdbcPosBalanceSyncStore store,
            JdbcLoyaltyStore loyalty,
            LoyaltyAdjustmentService adjustments,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            Clock clock) {
        this.store = store;
        this.loyalty = loyalty;
        this.adjustments = adjustments;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** The key a movement's ledger entry is written under; the same one a later, human-approved credit reuses. */
    public static String entryKey(UUID bindingId, String externalMovementId) {
        return "POSSYNC:" + bindingId + ":" + externalMovementId;
    }

    public static String actorOf(UUID bindingId) {
        return "system:pos-sync:" + bindingId;
    }

    @Transactional
    public Imported importMovement(
            UUID tenantId,
            UUID brandId,
            UUID bindingId,
            UUID runId,
            UUID accountId,
            TillMovement movement,
            long maxAutoCreditMinor,
            long maxBindingCreditMinorPerDay) {

        Instant now = clock.instant();
        UUID movementId = Ids.newId();
        boolean earn = movement.kind() == MovementKind.EARN;
        if (!store.insertMovement(
                movementId,
                tenantId,
                brandId,
                bindingId,
                accountId,
                runId,
                movement.externalMovementId(),
                earn ? "EARN" : "SPEND",
                movement.amountMinor(),
                movement.occurredAt(),
                now)) {
            return new Imported(Outcome.DUPLICATE, 0, 0, null);
        }

        AccountRow account = loyalty.findAccountById(tenantId, accountId).orElse(null);
        if (account == null || account.status() != AccountStatus.ACTIVE) {
            // An account that cannot take points is not the till's problem to solve by force. Recorded as seen and
            // refused; nothing moves.
            store.settleMovement(tenantId, movementId, "REJECTED", 0, 0, null, null);
            return new Imported(Outcome.REJECTED, 0, 0, null);
        }

        if (earn
                && exceedsCap(
                        tenantId,
                        bindingId,
                        accountId,
                        movement.amountMinor(),
                        maxAutoCreditMinor,
                        maxBindingCreditMinorPerDay,
                        now)) {
            UUID discrepancyId = Ids.newId();
            store.insertDiscrepancy(
                    discrepancyId,
                    tenantId,
                    brandId,
                    bindingId,
                    accountId,
                    runId,
                    null,
                    "CREDIT_HELD",
                    account.balanceMinor(),
                    null,
                    movement.amountMinor(),
                    movementId,
                    now);
            store.settleMovement(tenantId, movementId, "HELD", 0, 0, null, discrepancyId);
            audit.record(AuditFact.of("loyalty.pos_sync.held", AuditClass.BUSINESS)
                    .by(ActorRef.service(actorOf(bindingId)))
                    .at(ResourceScope.brand(tenantId, brandId))
                    .target("PosSyncMovement", movementId)
                    .because("A credit from the till is above the cap and waits for a person")
                    .changed(ChangeDocuments.created(Map.of(
                            "amountMinor", movement.amountMinor(), "accountId", accountId, "bindingId", bindingId)))
                    .correlatedBy(runId.toString())
                    .occurredAt(now)
                    .build());
            events.publishEvent(new LoyaltySyncDiscrepancyOpened(
                    tenantId, brandId, bindingId, discrepancyId, accountId, "CREDIT_HELD"));
            return new Imported(Outcome.HELD, 0, 0, discrepancyId);
        }

        TillMovementResult result = adjustments.applyTillMovement(new TillMovementCommand(
                tenantId,
                brandId,
                accountId,
                earn,
                movement.amountMinor(),
                earn ? PosBalanceSyncReasons.EARN : PosBalanceSyncReasons.SPEND,
                actorOf(bindingId),
                entryKey(bindingId, movement.externalMovementId()),
                runId.toString()));

        if (result.duplicate()) {
            // The ledger already holds this key: a crash of an earlier version of this method, never a normal path.
            store.settleMovement(tenantId, movementId, "APPLIED", movement.amountMinor(), 0, null, null);
            return new Imported(Outcome.APPLIED, movement.amountMinor(), 0, null);
        }

        UUID discrepancyId = null;
        String status = "APPLIED";
        if (result.absorbedMinor() > 0) {
            status = "PARTIALLY_APPLIED";
            discrepancyId = Ids.newId();
            store.insertDiscrepancy(
                    discrepancyId,
                    tenantId,
                    brandId,
                    bindingId,
                    accountId,
                    runId,
                    null,
                    "SPEND_EXCEEDS_BALANCE",
                    account.balanceMinor(),
                    null,
                    result.absorbedMinor(),
                    movementId,
                    now);
            events.publishEvent(new LoyaltySyncDiscrepancyOpened(
                    tenantId, brandId, bindingId, discrepancyId, accountId, "SPEND_EXCEEDS_BALANCE"));
        }
        store.settleMovement(
                tenantId,
                movementId,
                status,
                result.appliedMinor(),
                result.absorbedMinor(),
                result.entryId(),
                discrepancyId);
        return new Imported(
                result.absorbedMinor() > 0 ? Outcome.PARTIALLY_APPLIED : Outcome.APPLIED,
                result.appliedMinor(),
                result.absorbedMinor(),
                discrepancyId);
    }

    private boolean exceedsCap(
            UUID tenantId,
            UUID bindingId,
            UUID accountId,
            long amountMinor,
            long maxPerCustomer,
            long maxPerBinding,
            Instant now) {
        ZoneId zone = store.tenantZone(tenantId);
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant from = today.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        long customerSoFar = store.creditedBetween(tenantId, bindingId, accountId, from, to);
        if (customerSoFar + amountMinor > maxPerCustomer) {
            return true;
        }
        long bindingSoFar = store.creditedBetween(tenantId, bindingId, null, from, to);
        return bindingSoFar + amountMinor > maxPerBinding;
    }
}
