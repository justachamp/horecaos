package uz.horecaos.platform.pos.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.pos.application.PosOrderRevisionService;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.NoticeView;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.SendResponse;
import uz.horecaos.platform.pos.web.OrderPosRevisionController.TillRevisionResponse;
import uz.horecaos.platform.web.api.ApiException;
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

        TillRevisionResponse body = bodyOf(controller.forOrder(TENANT, ORDER));

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

        TillRevisionResponse body = bodyOf(controller.forOrder(TENANT, ORDER));

        assertThat(body.notice()).isNull();
        assertThat(body.canTell()).isFalse();
    }

    @Test
    @DisplayName("an order that does not exist is a 404 on every route")
    void anUnknownOrderIsNotFound() {
        when(orders.summary(TENANT, ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.forOrder(TENANT, ORDER)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.send(TENANT, ORDER)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.acknowledge(TENANT, ORDER)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("sending audits the attempt against the person, with money and codes only, and says why when it fails")
    void sendingIsAuditedAndAFailureIsExplainedInFixedWords() {
        orderExists();
        PosOrderRevisionService.View view = new PosOrderRevisionService.View(notice("OPEN"), true);
        when(revisions.tell(TENANT, ORDER, "operator-1"))
                .thenReturn(new PosOrderRevisionService.Result(
                        PosOrderRevisionService.Outcome.REJECTED, "CLOPOS_REFUSED", view));

        SendResponse body = bodyOf(controller.send(TENANT, ORDER));

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
        when(revisions.acknowledge(TENANT, ORDER, "operator-1")).thenReturn(true);

        TillRevisionResponse body = bodyOf(controller.acknowledge(TENANT, ORDER));

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
        assertThatThrownBy(() -> empty.acknowledge(TENANT, ORDER)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("both mutations declare pos.export.resolve and the read declares pos.export.read (ADR 0025)")
    void everyRouteDeclaresItsCapability() throws Exception {
        assertThat(capabilityOf("send")).isEqualTo(Capability.POS_EXPORT_RESOLVE);
        assertThat(capabilityOf("acknowledge")).isEqualTo(Capability.POS_EXPORT_RESOLVE);
        assertThat(capabilityOf("forOrder")).isEqualTo(Capability.POS_EXPORT_READ);
        Method send = OrderPosRevisionController.class.getMethod("send", UUID.class, UUID.class);
        assertThat(send.getAnnotation(RequiresCapability.class).mutating()).isTrue();
    }

    private static <T> T bodyOf(ResponseEntity<T> response) {
        return Objects.requireNonNull(response.getBody());
    }

    private static NoticeView noticeOf(TillRevisionResponse response) {
        return Objects.requireNonNull(response.notice());
    }

    private static Capability capabilityOf(String method) throws Exception {
        Method found = OrderPosRevisionController.class.getMethod(method, UUID.class, UUID.class);
        return found.getAnnotation(RequiresCapability.class).value();
    }
}
