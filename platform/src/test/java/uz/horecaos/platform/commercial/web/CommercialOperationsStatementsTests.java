package uz.horecaos.platform.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.StatementService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;

/**
 * Finance 8/X.4: the tenant-facing mirror of {@link CommercialStatementController}'s
 * three reads, at a path {@code frontend/operations}'s OpenAPI group can reach
 * (ADR 0057, ADR 0088).
 *
 * <p>No Spring context and no database: {@link StatementService} already
 * enforces tenant scoping at the store (proven elsewhere against the migrated
 * schema), so what this suite proves is narrower and just as real — that the
 * new controller passes the path's own {@code tenantId} through rather than
 * assuming one, that the wire shape is exactly {@link
 * CommercialStatementController.StatementView}'s (so the operations console
 * and the control-plane console parse the same response), and that the CSV
 * export carries the same body and the same download filename the
 * control-plane export already does.
 */
class CommercialOperationsStatementsTests {

    private static final UUID TENANT = UUID.fromString("018f9b10-4000-7000-8000-0000000000a1");
    private static final UUID STATEMENT_ID = UUID.fromString("018f9b10-4000-7000-8000-0000000000b1");

    private final StatementService statements = mock(StatementService.class);

    private final CommercialOperationsController controller = new CommercialOperationsController(
            mock(SubscriptionService.class),
            mock(EntitlementService.class),
            mock(UsageMeteringService.class),
            mock(PlanCatalogService.class),
            statements);

    @Test
    void listPassesTheirOwnTenantThroughAndMapsEveryStatement() {
        Statement issued = statement(STATEMENT_ID, Statement.ISSUED);
        Statement draftless = statement(null, Statement.VOID);
        when(statements.list(TENANT)).thenReturn(List.of(issued, draftless));

        ResponseEntity<List<CommercialStatementController.StatementView>> response = controller.statements(TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<CommercialStatementController.StatementView> body = Objects.requireNonNull(response.getBody());
        assertThat(body).hasSize(2);
        assertThat(body.get(0).statementId()).isEqualTo(STATEMENT_ID);
        assertThat(body.get(0).status()).isEqualTo(Statement.ISSUED);
        assertThat(body.get(0).total()).isNotNull();
        assertThat(Objects.requireNonNull(body.get(0).total()).amountMinor()).isEqualTo(1_500_000L);
        assertThat(body.get(0).lines()).hasSize(1);
        assertThat(body.get(1).statementId()).isNull();
    }

    @Test
    void oneStatementIsFoundByTenantAndId() {
        Statement issued = statement(STATEMENT_ID, Statement.ISSUED);
        when(statements.find(TENANT, STATEMENT_ID)).thenReturn(issued);

        ResponseEntity<CommercialStatementController.StatementView> response =
                controller.oneStatement(TENANT, STATEMENT_ID);

        verify(statements).find(TENANT, STATEMENT_ID);
        CommercialStatementController.StatementView body = Objects.requireNonNull(response.getBody());
        assertThat(body.number()).isEqualTo("S-2026-08-000001");
        assertThat(body.periodKey()).isEqualTo("2026-08");
    }

    @Test
    void exportIsTheSameCsvUnderTheSameFilenameAsTheControlPlaneExport() {
        Statement issued = statement(STATEMENT_ID, Statement.ISSUED);
        when(statements.find(TENANT, STATEMENT_ID)).thenReturn(issued);

        ResponseEntity<String> response = controller.statementExport(TENANT, STATEMENT_ID);

        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(Objects.requireNonNull(response.getHeaders().getContentType())
                        .toString())
                .startsWith("text/csv");
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("statement-S-2026-08-000001.csv");
        assertThat(response.getBody()).isEqualTo(CommercialStatementController.csv(issued));
    }

    private static Statement statement(@Nullable UUID id, String status) {
        return new Statement(
                id,
                TENANT,
                id == null ? null : "S-2026-08-000001",
                "2026-08",
                Instant.parse("2026-07-31T19:00:00Z"),
                Instant.parse("2026-08-31T19:00:00Z"),
                "UZS",
                1_500_000,
                null,
                status,
                "finance",
                Instant.parse("2026-09-01T05:00:00Z"),
                "August close",
                null,
                null,
                null,
                List.of(StatementLine.of(1, StatementLine.PLAN, "BASIC@v1", "BASIC v1, MONTHLY", 1, 1_500_000)));
    }
}
