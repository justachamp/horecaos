package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.ordering.application.OperatorTodayLeaderboardService;
import uz.horecaos.platform.ordering.application.OperatorTodayLeaderboardService.Leaderboard;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OperatorTodayLeaderboardRow;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Gap map row 0.1d, the live operator band: who is taking and confirming orders
 * during service, with names (ADR 0139).
 *
 * <p>Served at the two levels the live board itself is read at, each declared at
 * its own scope because a grant covers only the routes whose path names its
 * level (ADR 0025): the brand route for a brand-level reader, the branch route
 * for a shift supervisor who holds {@code ORDER_READ} at their own branch alone.
 * Both need the same capability that reads the board, and names are visible to
 * its holders -- this record makes no separate gate for them.
 *
 * <p><strong>A live ranking of named employees is workplace monitoring in a way a
 * closed-day report is not.</strong> ADR 0139 accepts it as the default and
 * leaves the wallboard variant an open choice for product and legal, so each row
 * carries {@code displayName} <em>and</em> the non-personal {@code
 * displayReference}-free {@code operatorPrincipalId}-independent fields a TV can
 * choose between: a surface that must not show a name simply does not render
 * {@code displayName}. Pseudo-operators never appear here -- a bot or a website
 * order names a customer or a channel in the actor columns, and this reads only
 * {@code USER} actors.
 */
@RestController
@Tag(name = "Operator order counts", description = "The live operator band: orders taken and accepted today (row 0.1d)")
public class OperatorTodayLeaderboardController {

    private final OperatorTodayLeaderboardService leaderboard;
    private final StaffDirectory staffDirectory;

    public OperatorTodayLeaderboardController(
            OperatorTodayLeaderboardService leaderboard, StaffDirectory staffDirectory) {
        this.leaderboard = leaderboard;
        this.staffDirectory = staffDirectory;
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/orders/operators/today")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Today's operators across the brand: orders taken and accepted, with names",
            description = "Counting read of ordering.orders for the tenant's business day (ADR 0043), "
                    + "most accepted first, at most a hundred. `createdCount` and `acceptedCount` can both "
                    + "include the same order and are never added together. `displayName` is the tenant's "
                    + "own name for the person, or null for one it has none for. ORDER_READ at BRAND scope: "
                    + "a location-scoped grant is refused and reads its own branch's route instead.")
    public ResponseEntity<OperatorTodayLeaderboardResponse> forBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(respond(tenantId, leaderboard.today(tenantId, brandId, null)));
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/operators/today")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Today's operators at one branch: orders taken and accepted, with names",
            description = "The shift supervisor's band, for the branch their own grant covers.")
    public ResponseEntity<OperatorTodayLeaderboardResponse> forLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(respond(tenantId, leaderboard.today(tenantId, brandId, locationId)));
    }

    private OperatorTodayLeaderboardResponse respond(UUID tenantId, Leaderboard board) {
        Set<String> subjects = new HashSet<>();
        board.rows().forEach(row -> subjects.add(row.operatorSubject()));
        Map<String, String> names = staffDirectory.namesOf(tenantId, subjects);
        List<OperatorTodayRowResponse> rows = board.rows().stream()
                .map(row -> OperatorTodayRowResponse.of(row, names.get(row.operatorSubject())))
                .toList();
        return new OperatorTodayLeaderboardResponse(
                rows, board.window().from(), board.window().to());
    }

    public record OperatorTodayRowResponse(
            String operatorPrincipalId,
            @Classified(DataClass.PERSONAL) @Nullable String displayName,
            long createdCount,
            long acceptedCount) {

        static OperatorTodayRowResponse of(OperatorTodayLeaderboardRow row, @Nullable String displayName) {
            return new OperatorTodayRowResponse(row.operatorSubject(), displayName, row.created(), row.accepted());
        }

        @Override
        public String toString() {
            return "OperatorTodayRowResponse[createdCount=" + createdCount + ", acceptedCount=" + acceptedCount + "]";
        }
    }

    public record OperatorTodayLeaderboardResponse(
            List<OperatorTodayRowResponse> rows, Instant businessDayFrom, Instant businessDayTo) {}
}
