package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.commercial.application.ArrearsService;
import uz.horecaos.platform.commercial.application.CardChargingAvailability;
import uz.horecaos.platform.commercial.application.PlatformBillingSettingsService;
import uz.horecaos.platform.commercial.application.WalletService;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcArrearsStore.ArrearRow;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore.OpenDue;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The arrears board (ADR 0089): every tenant past due or suspended, how long,
 * and what each stage restricts.
 *
 * <p>What a stage restricts is read from the subscription lifecycle itself, so
 * the board and the entitlement resolver cannot disagree about it.
 */
@RestController
@Tag(name = "Arrears", description = "Tenants past due or suspended, and what each stage restricts")
public class ArrearsController {

    /** The stages a tenant in arrears moves through, in order. */
    private static final List<SubscriptionStatus> STAGES = List.of(
            SubscriptionStatus.ACTIVE,
            SubscriptionStatus.PAST_DUE,
            SubscriptionStatus.SUSPENDED,
            SubscriptionStatus.TERMINATED);

    private final ArrearsService arrears;
    private final WalletService wallet;
    private final CardChargingAvailability cardCharging;
    private final PlatformBillingSettingsService billingSettings;
    private final Clock clock;

    public ArrearsController(
            ArrearsService arrears,
            WalletService wallet,
            CardChargingAvailability cardCharging,
            PlatformBillingSettingsService billingSettings,
            Clock clock) {
        this.arrears = arrears;
        this.wallet = wallet;
        this.cardCharging = cardCharging;
        this.billingSettings = billingSettings;
        this.clock = clock;
    }

    @GetMapping("/api/v1/control-plane/arrears")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Tenants in arrears, longest first",
            description = "Past-due and suspended subscriptions with how long each has been there and "
                    + "the last statement issued, beside what each stage restricts. owed is what the tenant "
                    + "still owes on issued statements, and depositDue the activation deposit it owes beside "
                    + "them, which is on no statement; paidInFull is true when it owes neither. For a PAST_DUE "
                    + "tenant, whose stage is about money by definition, that is the cue to restore it. For a "
                    + "SUSPENDED one it is a fact and not a cue: the suspension's reason is free text, so the "
                    + "platform cannot tell whether paying addressed it — nothing moves a subscription by "
                    + "itself (ADR 0089), so moving a tenant between stages stays the subscription transition, "
                    + "with its reason.")
    public ResponseEntity<ArrearsBoardView> board() {
        Instant now = clock.instant();
        ArrearsService.Board board = arrears.board();
        Map<UUID, OpenDue> owed = wallet.openDueByTenant(
                board.rows().stream().map(ArrearRow::tenantId).distinct().toList());
        return ResponseEntity.ok(new ArrearsBoardView(
                STAGES.stream().map(StageView::of).toList(),
                board.rows().stream()
                        .map(row -> ArrearView.of(
                                row, board.latestStatements().get(row.tenantId()), owed.get(row.tenantId()), now))
                        .toList()));
    }

    @GetMapping("/api/v1/tenants/{tenantId}/commercial/arrears")
    @RequiresCapability(value = Capability.COMMERCIAL_ARREARS_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "This tenant's own place in the arrears lifecycle",
            description = "Where the subscription is right now, how long it has been there, and what "
                    + "that stage restricts — the tenant-reachable, single-row mirror of the platform "
                    + "board above (ADR 0127). A tenant in good standing gets ACTIVE with nothing "
                    + "restricted, not a 404: this is a state read, not an arrears-only one. owed is what "
                    + "the tenant still owes on issued statements (null when nothing), and waysToPay says "
                    + "which of paying by card or by bank transfer is available now, so the banner can "
                    + "offer a way out instead of only describing the restriction.")
    public ResponseEntity<TenantArrearsView> tenantArrears(@PathVariable UUID tenantId) {
        Instant now = clock.instant();
        ArrearsService.TenantArrears found = arrears.forTenant(tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no subscription"));
        OpenDue owed = wallet.openDueByTenant(List.of(tenantId)).get(tenantId);
        return ResponseEntity.ok(TenantArrearsView.of(
                found,
                owed,
                new WaysToPayView(
                        wallet.cardOnFile(tenantId).isPresent(),
                        cardCharging.available(),
                        billingSettings.current().configured()),
                now));
    }

    /** The board: the stages, then the tenants in them. */
    public record ArrearsBoardView(List<StageView> stages, List<ArrearView> subscriptions) {}

    /** This tenant's own row: its stage, how long it has been there, what that stage restricts, and what is owed. */
    public record TenantArrearsView(
            String status,
            boolean planEntitlementsApply,
            boolean additionsBlocked,
            List<String> allowedNext,
            String since,
            long daysInStatus,
            @Nullable String suspensionReason,
            @Nullable LatestStatementView latestStatement,
            @Nullable OwedView owed,
            WaysToPayView waysToPay) {

        static TenantArrearsView of(
                ArrearsService.TenantArrears found, @Nullable OpenDue owed, WaysToPayView waysToPay, Instant now) {
            ArrearRow row = found.row();
            Statement latest = found.latestStatement();
            return new TenantArrearsView(
                    row.status().name(),
                    row.status().grantsPlanEntitlements(),
                    row.status().blocksAdditions(),
                    row.status().allowedNext().stream().map(Enum::name).sorted().toList(),
                    row.since().toString(),
                    Math.max(0, Duration.between(row.since(), now).toDays()),
                    row.suspensionReason(),
                    latest == null ? null : LatestStatementView.of(latest),
                    OwedView.of(owed),
                    waysToPay);
        }
    }

    /** What the tenant still owes on issued statements, and on how many. */
    public record OwedView(ApiMoney due, int openStatements) {

        static @Nullable OwedView of(@Nullable OpenDue owed) {
            return owed == null ? null : new OwedView(ApiMoney.of(owed.dueMinor(), owed.currency()), owed.statements());
        }
    }

    /** Which ways of paying exist for this tenant right now. */
    public record WaysToPayView(boolean cardOnFile, boolean cardPaymentsAvailable, boolean bankTransferAvailable) {}

    /** What one stage does to a tenant. */
    public record StageView(
            String status, boolean planEntitlementsApply, boolean additionsBlocked, List<String> allowedNext) {

        static StageView of(SubscriptionStatus status) {
            return new StageView(
                    status.name(),
                    status.grantsPlanEntitlements(),
                    status.blocksAdditions(),
                    status.allowedNext().stream().map(Enum::name).sorted().toList());
        }
    }

    /** One tenant in arrears. */
    public record ArrearView(
            UUID tenantId,
            String tenantName,
            UUID subscriptionId,
            String status,
            String since,
            long daysInStatus,
            String planCode,
            int planVersionNumber,
            long version,
            List<String> allowedNext,
            @Nullable String suspensionReason,
            @Nullable LatestStatementView latestStatement,
            @Nullable OwedView owed,
            @Nullable ApiMoney depositDue,
            boolean paidInFull) {

        static ArrearView of(ArrearRow row, @Nullable Statement latest, @Nullable OpenDue owed, Instant now) {
            ApiMoney depositDue =
                    row.depositDueMinor() > 0 ? ApiMoney.of(row.depositDueMinor(), row.planCurrency()) : null;
            return new ArrearView(
                    row.tenantId(),
                    row.tenantName(),
                    row.subscriptionId(),
                    row.status().name(),
                    row.since().toString(),
                    Math.max(0, Duration.between(row.since(), now).toDays()),
                    row.planCode(),
                    row.versionNumber(),
                    row.version(),
                    row.status().allowedNext().stream().map(Enum::name).sorted().toList(),
                    row.suspensionReason(),
                    latest == null ? null : LatestStatementView.of(latest),
                    OwedView.of(owed),
                    depositDue,
                    owed == null && depositDue == null);
        }
    }

    /** The newest standing statement of a tenant on the board. */
    public record LatestStatementView(
            UUID statementId, String number, String periodKey, ApiMoney total, String issuedAt) {

        static @Nullable LatestStatementView of(Statement statement) {
            UUID id = statement.id();
            String number = statement.number();
            String currency = statement.currency();
            Instant issuedAt = statement.issuedAt();
            if (id == null || number == null || currency == null || issuedAt == null) {
                return null;
            }
            return new LatestStatementView(
                    id,
                    number,
                    statement.periodKey(),
                    ApiMoney.of(statement.totalMinor(), currency),
                    issuedAt.toString());
        }
    }
}
