package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.commercial.application.ArrearsService;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcArrearsStore.ArrearRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiMoney;
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
    private final Clock clock;

    public ArrearsController(ArrearsService arrears, Clock clock) {
        this.arrears = arrears;
        this.clock = clock;
    }

    @GetMapping("/api/v1/control-plane/arrears")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Tenants in arrears, longest first",
            description = "Past-due and suspended subscriptions with how long each has been there and "
                    + "the last statement issued, beside what each stage restricts. Moving a tenant "
                    + "between stages is the subscription transition, with its reason.")
    public ResponseEntity<ArrearsBoardView> board() {
        Instant now = clock.instant();
        ArrearsService.Board board = arrears.board();
        return ResponseEntity.ok(new ArrearsBoardView(
                STAGES.stream().map(StageView::of).toList(),
                board.rows().stream()
                        .map(row -> ArrearView.of(row, board.latestStatements().get(row.tenantId()), now))
                        .toList()));
    }

    /** The board: the stages, then the tenants in them. */
    public record ArrearsBoardView(List<StageView> stages, List<ArrearView> subscriptions) {}

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
            @Nullable LatestStatementView latestStatement) {

        static ArrearView of(ArrearRow row, @Nullable Statement latest, Instant now) {
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
                    latest == null ? null : LatestStatementView.of(latest));
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
