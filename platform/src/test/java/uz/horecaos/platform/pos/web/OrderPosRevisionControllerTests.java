package uz.horecaos.platform.pos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.pos.application.PosOrderRevisionService;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.TillRevisionActionRequest;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.TillRevisionNoticeView;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.TillRevisionResponse;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.TillRevisionSendResponse;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The console's door to a till revision (ADR 0137, row 4.2c): what it reads, what it sends, what it audits,
 * and that neither mutation is reachable without {@code pos.export.resolve}.
 */
class OrderPosRevisionControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    /** What the console quotes: the revision of the notice it showed (the fixture notice is revision 2). */
    private static final TillRevisionActionRequest SAW_REVISION_2 = new TillRevisionActionRequest(2);

    private final OrderDirectory orders = mock(OrderDirectory.class);
    private final PosOrderRevisionService revisions = mock(PosOrderRevisionService.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final OrderPosRevisionController controller = new OrderPosRevisionController(
            orders,
            revisions,
            audit,
            () -> new AuthenticatedActor("operator-1", Set.of(), Map.of()),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static Notice notice(String status) {
        return new Notice(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                ORDER,
                "CATCHWEIGHT",
                2,
                "UZS",
                215_000,
                208_000,
                status,
                NOW,
                "OPEN".equals(status) ? null : NOW,
                0,
                null);
    }

    private void orderExists() {
        when(orders.summary(TENANT, ORDER))
                .thenReturn(Optional.of(new OrderDirectory.OrderSummary(
                        ORDER, TENANT, BRAND, LOCATION, "A-1042", null, "guest", "READY", "UZS", 208_000, 5)));
    }

    @Test
    @DisplayName(
            "the read names what the till holds against what the order is worth, and whether the platform can tell it")
    void theReadStatesTheDifference() {
        orderExists();
        when(revisions.view(TENANT, ORDER)).thenReturn(new PosOrderRevisionService.View(notice("OPEN"), true));

        TillRevisionResponse body = bodyOf(controller.tillRevisionForOrder(TENANT, BRAND, LOCATION, ORDER));

        assertThat(body.canTell()).isTrue();
        assertThat(noticeOf(body).tillTotalMinor()).isEqualTo(215_000);
        assertThat(noticeOf(body).revisedTotalMinor()).isEqualTo(208_000);
        assertThat(noticeOf(body).deltaMinor()).isEqualTo(-7_000);
        assertThat(noticeOf(body).status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("an order with no notice reads as agreeing, not as an error")
    void noNoticeIsNotAnError() {
        orderExists();
        when(revisions.view(TENANT, ORDER)).thenReturn(new PosOrderRevisionService.View(null, false));

        TillRevisionResponse body = bodyOf(controller.tillRevisionForOrder(TENANT, BRAND, LOCATION, ORDER));

        assertThat(body.notice()).isNull();
        assertThat(body.canTell()).isFalse();
    }

    @Test
    @DisplayName("an order that does not exist is a 404 on every route")
    void anUnknownOrderIsNotFound() {
        when(orders.summary(TENANT, ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.tillRevisionForOrder(TENANT, BRAND, LOCATION, ORDER))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.sendTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.acknowledgeTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an order of another branch is a 404 on every route, even for a caller who holds the capability")
    void anOrderOfAnotherBranchIsNotFound() {
        orderExists();
        UUID otherLocation = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();

        assertThatThrownBy(() -> controller.tillRevisionForOrder(TENANT, BRAND, otherLocation, ORDER))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.sendTillRevision(TENANT, BRAND, otherLocation, ORDER, SAW_REVISION_2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () -> controller.acknowledgeTillRevision(TENANT, otherBrand, LOCATION, ORDER, SAW_REVISION_2))
                .isInstanceOf(ApiException.class);
        verify(revisions, never()).tell(any(), any(), any(), anyInt());
        verify(revisions, never()).acknowledge(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("sending audits the attempt against the person, with money and codes only, and says why when it fails")
    void sendingIsAuditedAndAFailureIsExplainedInFixedWords() {
        orderExists();
        PosOrderRevisionService.View view = new PosOrderRevisionService.View(notice("OPEN"), true);
        when(revisions.tell(TENANT, ORDER, "operator-1", 2))
                .thenReturn(new PosOrderRevisionService.Result(
                        PosOrderRevisionService.Outcome.REJECTED, "CLOPOS_REFUSED", view));

        TillRevisionSendResponse body =
                bodyOf(controller.sendTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2));

        assertThat(body.outcome()).isEqualTo("REJECTED");
        assertThat(body.code()).isEqualTo("CLOPOS_REFUSED");
        assertThat(body.message()).contains("refused");
        assertThat(noticeOf(body.revision()).status()).isEqualTo("OPEN");
        verify(audit).record(any(AuditFact.class));
    }

    @Test
    @DisplayName(
            "acknowledging closes the open notice and audits it; with none open it is a conflict and writes nothing")
    void acknowledgingClosesOrConflicts() {
        orderExists();
        Notice open = notice("OPEN");
        when(revisions.view(TENANT, ORDER))
                .thenReturn(new PosOrderRevisionService.View(open, false))
                .thenReturn(new PosOrderRevisionService.View(notice("ACKNOWLEDGED"), false));
        when(revisions.acknowledge(TENANT, ORDER, "operator-1", 2)).thenReturn(true);

        TillRevisionResponse body =
                bodyOf(controller.acknowledgeTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2));

        assertThat(noticeOf(body).status()).isEqualTo("ACKNOWLEDGED");
        verify(audit).record(any(AuditFact.class));

        PosOrderRevisionService none = mock(PosOrderRevisionService.class);
        when(none.view(eq(TENANT), eq(ORDER))).thenReturn(new PosOrderRevisionService.View(null, false));
        OrderPosRevisionController empty = new OrderPosRevisionController(
                orders,
                none,
                audit,
                () -> new AuthenticatedActor("operator-1", Set.of(), Map.of()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> empty.acknowledgeTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an action on a notice that moved is a 409 STALE_VERSION naming both revisions, and writes nothing")
    void aStaleActionIsAConflictWithBothRevisions() {
        orderExists();
        when(revisions.view(TENANT, ORDER)).thenReturn(new PosOrderRevisionService.View(notice("OPEN"), true));
        when(revisions.tell(TENANT, ORDER, "operator-1", 2))
                .thenThrow(new PosOrderRevisionService.StaleNoticeException(2, 3));
        when(revisions.acknowledge(TENANT, ORDER, "operator-1", 2))
                .thenThrow(new PosOrderRevisionService.StaleNoticeException(2, 3));

        for (Runnable action : List.<Runnable>of(
                () -> controller.sendTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2),
                () -> controller.acknowledgeTillRevision(TENANT, BRAND, LOCATION, ORDER, SAW_REVISION_2))) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, conflict -> {
                assertThat(conflict.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                assertThat(conflict.properties()).containsEntry("expectedVersion", 2L);
                assertThat(conflict.properties()).containsEntry("currentVersion", 3L);
            });
        }
        verify(audit, never()).record(any(AuditFact.class));
    }

    @Test
    @DisplayName("the console cannot omit the revision it saw: a body without it is not an action")
    void theRevisionIsRequired() {
        assertThat(TillRevisionActionRequest.class
                        .getRecordComponents()[0]
                        .getAccessor()
                        .getReturnType())
                .as("boxed, so a missing field is a validation failure and not a quiet zero (Jackson 3)")
                .isEqualTo(Integer.class);
        for (String action : new String[] {"sendTillRevision", "acknowledgeTillRevision"}) {
            assertThat(methodNamed(action).getParameters()[4].isAnnotationPresent(jakarta.validation.Valid.class))
                    .as(action)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("every route is checked at the branch in its path, not at the tenant (ADR 0025)")
    void everyRouteIsCheckedAtTheBranch() {
        for (String method : new String[] {"tillRevisionForOrder", "sendTillRevision", "acknowledgeTillRevision"}) {
            assertThat(methodNamed(method)
                            .getAnnotation(RequiresCapability.class)
                            .scope())
                    .as(method)
                    .isEqualTo(ScopeType.LOCATION);
        }
    }

    @Test
    @DisplayName("both mutations declare pos.export.resolve and the read declares pos.export.read (ADR 0025)")
    void everyRouteDeclaresItsCapability() {
        assertThat(capabilityOf("sendTillRevision")).isEqualTo(Capability.POS_EXPORT_RESOLVE);
        assertThat(capabilityOf("acknowledgeTillRevision")).isEqualTo(Capability.POS_EXPORT_RESOLVE);
        assertThat(capabilityOf("tillRevisionForOrder")).isEqualTo(Capability.POS_EXPORT_READ);
        assertThat(methodNamed("sendTillRevision")
                        .getAnnotation(RequiresCapability.class)
                        .mutating())
                .isTrue();
        assertThat(methodNamed("acknowledgeTillRevision")
                        .getAnnotation(RequiresCapability.class)
                        .mutating())
                .isTrue();
    }

    private static <T> T bodyOf(ResponseEntity<T> response) {
        return Objects.requireNonNull(response.getBody());
    }

    private static TillRevisionNoticeView noticeOf(TillRevisionResponse response) {
        return Objects.requireNonNull(response.notice());
    }

    private static Capability capabilityOf(String method) {
        return methodNamed(method).getAnnotation(RequiresCapability.class).value();
    }

    private static Method methodNamed(String name) {
        return java.util.Arrays.stream(OrderPosRevisionController.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
