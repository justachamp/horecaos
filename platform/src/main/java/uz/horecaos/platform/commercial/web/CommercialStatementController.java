package uz.horecaos.platform.commercial.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.StatementService;
import uz.horecaos.platform.commercial.application.WalletService;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiMoney;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Monthly statements (ADR 0088): what a tenant owes under its plan and
 * modules for one month, before tax.
 *
 * <p>Reading them is the tenant's too, under {@code commercial.usage.read};
 * issuing and voiding are HorecaOS staff's alone.
 */
@RestController
@Tag(name = "Commercial statements", description = "Monthly statements of what a tenant owes, before tax")
public class CommercialStatementController {

    private final StatementService statements;

    /**
     * Only to collect what a statement still owes from a card, once the
     * statement's own transaction has committed -- see {@link
     * WalletService#settleCardRemainders}.
     */
    private final WalletService wallet;

    private final CurrentActor currentActor;

    public CommercialStatementController(StatementService statements, WalletService wallet, CurrentActor currentActor) {
        this.statements = statements;
        this.wallet = wallet;
        this.currentActor = currentActor;
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/statements")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every statement the tenant has been issued",
            description = "Newest month first, void ones included.")
    public ResponseEntity<List<StatementView>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                statements.list(tenantId).stream().map(StatementView::of).toList());
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/statements/draft")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "What a month would be billed if it were issued now",
            description = "Computed each time from the plan in force, the modules live during the month "
                    + "and the metered usage. Allowed for the month in progress.")
    public ResponseEntity<StatementView> draft(
            @PathVariable UUID tenantId, @RequestParam @Pattern(regexp = "\\d{4}-\\d{2}") String periodKey) {
        return ResponseEntity.ok(StatementView.of(statements.draft(tenantId, periodKey)));
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/statements/{statementId}")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(summary = "One issued statement with its lines")
    public ResponseEntity<StatementView> find(@PathVariable UUID tenantId, @PathVariable UUID statementId) {
        return ResponseEntity.ok(StatementView.of(statements.find(tenantId, statementId)));
    }

    @GetMapping(
            path = "/api/v1/control-plane/tenants/{tenantId}/statements/{statementId}/export",
            produces = "text/csv")
    @RequiresCapability(value = Capability.COMMERCIAL_USAGE_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "One issued statement as CSV",
            description = "For the accounting system an invoice is made in. Amounts are integer minor "
                    + "units of the statement's currency.")
    public ResponseEntity<String> export(@PathVariable UUID tenantId, @PathVariable UUID statementId) {
        Statement statement = statements.find(tenantId, statementId);
        String filename = "statement-" + statement.number() + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(csv(statement));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/statements")
    @RequiresCapability(value = Capability.COMMERCIAL_STATEMENT_ISSUE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Issue a month's statement",
            description = "Only for a month that has ended, and only one standing statement per month: "
                    + "void the one that is wrong, then issue again.")
    public ResponseEntity<StatementIssued> issue(
            @PathVariable UUID tenantId, @Valid @RequestBody StatementIssueRequest body) {
        StatementService.IssuedRef issued =
                statements.issue(tenantId, body.periodKey(), actor(), body.reason(), correlationId());
        // Once the money is committed, and never inside its transaction: the
        // card provider is a third party, and asking it from inside the unit of
        // work that recorded this money would let a provider timeout roll that
        // money back (ADR 0095).
        wallet.settleCardRemainders(tenantId);
        return ResponseEntity.ok(new StatementIssued(issued.statementId(), issued.number()));
    }

    @PostMapping("/api/v1/platform-admin/commercial/tenants/{tenantId}/statements/{statementId}/void")
    @RequiresCapability(value = Capability.COMMERCIAL_STATEMENT_ISSUE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Void an issued statement",
            description = "It stays on record as void; the month is free to issue again.")
    public ResponseEntity<Void> voidStatement(
            @PathVariable UUID tenantId, @PathVariable UUID statementId, @Valid @RequestBody ReasonRequest body) {
        statements.voidStatement(tenantId, statementId, actor(), body.reason(), correlationId());
        // Once the money is committed, and never inside its transaction: the
        // card provider is a third party, and asking it from inside the unit of
        // work that recorded this money would let a provider timeout roll that
        // money back (ADR 0095).
        wallet.settleCardRemainders(tenantId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ csv

    /** The statement as CSV, one row per charge, each carrying the statement it belongs to. */
    static String csv(Statement statement) {
        StringBuilder out = new StringBuilder();
        out.append(
                "number,period,status,line,kind,reference,description,quantity,unit_price_minor,amount_minor,currency\r\n");
        for (StatementLine line : statement.lines()) {
            out.append(String.join(
                            ",",
                            cell(String.valueOf(statement.number())),
                            cell(statement.periodKey()),
                            cell(statement.status()),
                            String.valueOf(line.lineNumber()),
                            cell(line.kind()),
                            cell(line.referenceCode()),
                            cell(line.description()),
                            String.valueOf(line.quantity()),
                            String.valueOf(line.unitPriceMinor()),
                            String.valueOf(line.amountMinor()),
                            cell(String.valueOf(statement.currency()))))
                    .append("\r\n");
        }
        return out.toString();
    }

    /**
     * One quoted CSV field.
     *
     * <p>A description is text a person typed into a plan or module name, and a
     * spreadsheet evaluates a cell beginning with {@code = + - @} as a formula;
     * such a value is prefixed with an apostrophe so it opens as text.
     */
    static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    // --------------------------------------------------------- wire records

    /** A statement, drafted or issued, with its lines. */
    public record StatementView(
            @Nullable UUID statementId,
            @Nullable String number,
            String periodKey,
            String periodStart,
            String periodEnd,
            String status,
            @Nullable ApiMoney total,
            @Nullable String issuedBy,
            @Nullable String issuedAt,
            @Nullable String issueReason,
            @Nullable String voidedBy,
            @Nullable String voidedAt,
            @Nullable String voidReason,
            List<StatementLineView> lines) {

        static StatementView of(Statement statement) {
            String currency = statement.currency();
            return new StatementView(
                    statement.id(),
                    statement.number(),
                    statement.periodKey(),
                    statement.periodStart().toString(),
                    statement.periodEnd().toString(),
                    statement.status(),
                    currency == null ? null : ApiMoney.of(statement.totalMinor(), currency),
                    statement.issuedBy(),
                    text(statement.issuedAt()),
                    statement.issueReason(),
                    statement.voidedBy(),
                    text(statement.voidedAt()),
                    statement.voidReason(),
                    currency == null
                            ? List.of()
                            : statement.lines().stream()
                                    .map(line -> StatementLineView.of(line, currency))
                                    .toList());
        }
    }

    /** One charge on a statement. */
    public record StatementLineView(
            int lineNumber,
            String kind,
            String referenceCode,
            String description,
            long quantity,
            ApiMoney unitPrice,
            ApiMoney amount) {

        static StatementLineView of(StatementLine line, String currency) {
            return new StatementLineView(
                    line.lineNumber(),
                    line.kind(),
                    line.referenceCode(),
                    line.description(),
                    line.quantity(),
                    ApiMoney.of(line.unitPriceMinor(), currency),
                    ApiMoney.of(line.amountMinor(), currency));
        }
    }

    private static @Nullable String text(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public record StatementIssueRequest(
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String periodKey,
            @NotBlank @Size(max = 1000) String reason) {}

    /** The id and number an issued statement was filed under. */
    public record StatementIssued(UUID statementId, String number) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
}
