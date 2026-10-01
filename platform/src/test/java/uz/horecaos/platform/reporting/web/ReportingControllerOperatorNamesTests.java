package uz.horecaos.platform.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.application.ReportQueryService;
import uz.horecaos.platform.reporting.application.ReportQueryService.OperatorLeaderboardResult;
import uz.horecaos.platform.reporting.application.ReportQueryService.OperatorLeaderboardRow;
import uz.horecaos.platform.reporting.application.ReportQueryService.Provenance;
import uz.horecaos.platform.reporting.web.ReportingController.OperatorLeaderboardResponse;
import uz.horecaos.platform.support.StaffDirectories;

/**
 * Gap map row 7.5/0.1d: names on the staff report (ADR 0139) are composed at the
 * web layer from the tenant's own directory and never in {@code reporting}, so the
 * leaderboard cannot disagree with the audit log about who someone is, and a
 * channel pseudo-operator -- not a person -- keeps its typed rendering.
 */
class ReportingControllerOperatorNamesTests {

    private static final UUID TENANT = UUID.fromString("018fb300-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb300-4000-7000-8000-0000000000a2");
    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-30");

    private ReportQueryService queries;
    private StaffDirectories.Fake directory;
    private ReportingController controller;

    @BeforeEach
    void setUp() {
        queries = mock(ReportQueryService.class);
        directory = StaffDirectories.fake();
        controller = new ReportingController(queries, directory);
    }

    private static <T> T requireBody(org.springframework.http.ResponseEntity<T> response) {
        return java.util.Objects.requireNonNull(response.getBody());
    }

    private static OperatorLeaderboardRow staff(String subject) {
        return new OperatorLeaderboardRow(
                subject, "STAFF", subject, 12, 600_000L, 580_000L, 50_000L, 90, 4, 6, 2, 3.1, List.of());
    }

    private static OperatorLeaderboardRow machine(String channel) {
        return new OperatorLeaderboardRow(
                "channel:" + channel,
                "MACHINE",
                channel,
                30,
                900_000L,
                880_000L,
                30_000L,
                null,
                30,
                0,
                0,
                2.0,
                List.of());
    }

    private static Provenance provenance() {
        return new Provenance(
                Instant.parse("2026-10-01T00:00:00Z"),
                FROM,
                null,
                "04:00",
                "Asia/Tashkent",
                1,
                List.of(),
                List.of(),
                0);
    }

    @Test
    @DisplayName("a staff row carries the tenant's name for the person; a channel row carries none")
    void staffRowsAreNamedAndChannelRowsAreNot() {
        directory.name(TENANT, "aziza-subject", "Aziza Karimova");
        when(queries.operatorLeaderboard(eq(TENANT), eq(FROM), eq(TO), anyList()))
                .thenReturn(new OperatorLeaderboardResult(
                        List.of(staff("aziza-subject"), machine("BOT"), staff("unnamed-subject")), provenance()));

        OperatorLeaderboardResponse response = requireBody(controller.operatorLeaderboard(TENANT, FROM, TO, List.of()));

        assertThat(response.rows()).extracting(row -> row.displayName()).containsExactly("Aziza Karimova", null, null);
        assertThat(response.rows().get(1).principalKind()).isEqualTo("MACHINE");
        assertThat(response.rows().get(1).subject()).isEqualTo("BOT");
    }

    @Test
    @DisplayName("a name another tenant keeps for the same subject never appears on this tenant's report")
    void aNameFromAnotherTenantNeverAppears() {
        directory.name(OTHER_TENANT, "shared-subject", "Their Colleague");
        when(queries.operatorLeaderboard(eq(TENANT), eq(FROM), eq(TO), anyList()))
                .thenReturn(new OperatorLeaderboardResult(List.of(staff("shared-subject")), provenance()));

        OperatorLeaderboardResponse response = requireBody(controller.operatorLeaderboard(TENANT, FROM, TO, List.of()));

        assertThat(response.rows().getFirst().displayName()).isNull();
    }

    @Test
    @DisplayName("the drill-down names the operator once, and never a channel")
    void theDrillDownNamesTheOperator() {
        directory.name(TENANT, "aziza-subject", "Aziza Karimova");
        when(queries.operatorProducts(eq(TENANT), eq("aziza-subject"), eq(FROM), eq(TO), anyList(), any(Integer.class)))
                .thenReturn(new ReportQueryService.OperatorProductResult(List.of(), false, provenance()));
        when(queries.operatorProducts(eq(TENANT), eq("channel:BOT"), eq(FROM), eq(TO), anyList(), any(Integer.class)))
                .thenReturn(new ReportQueryService.OperatorProductResult(List.of(), false, provenance()));

        assertThat(requireBody(controller.operatorProducts(TENANT, "aziza-subject", FROM, TO, List.of(), 10))
                        .operatorDisplayName())
                .isEqualTo("Aziza Karimova");
        assertThat(requireBody(controller.operatorProducts(TENANT, "channel:BOT", FROM, TO, List.of(), 10))
                        .operatorDisplayName())
                .isNull();
    }
}
