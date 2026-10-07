package uz.horecaos.platform.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.application.EInvoicingService;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.EInvoicingInstallation;
import uz.horecaos.platform.commercial.domain.EInvoicingLineClassification;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcEInvoiceStore.Environment;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What the e-invoicing endpoints say and do not say (ADR 0096): the wire records map the service's
 * answers faithfully, hand it the caller as the actor, and never carry a secret reference.
 *
 * <p>Authorization is not asserted here: {@code EndpointCapabilityDeclarationTests} scans every
 * endpoint for exactly one declaration and for replay protection on every mutation.
 */
class CommercialEInvoicingControllerTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-6000-7000-8000-0000000000a1");
    private static final UUID STATEMENT = UUID.fromString("018f9c10-6000-7000-8000-0000000000b1");
    private static final UUID INSTALLATION = UUID.fromString("018f9c10-5000-7000-8000-0000000000d1");
    private static final String REFERENCE = "horecaos:production:provider_einvoicing:platform:operator-secret-ref";

    private final EInvoicingService service = mock(EInvoicingService.class);
    private final CommercialEInvoicingController controller = new CommercialEInvoicingController(
            service, () -> new AuthenticatedActor("finance.staff", Set.of("platform-admin"), Map.of()));

    @Test
    @DisplayName("an account is shown with whether it is bound and what it lacks, and never its secret reference")
    void anAccountNeverShowsItsReference() {
        EInvoicingInstallation bound = installation("ACTIVE", REFERENCE);
        EInvoicingInstallation unbound = installation("DRAFT", null);
        Environment environment = new Environment(
                "didox_production", "DIDOX", "https://api-partners.didox.uz", true, "api-partners.didox.uz");
        when(service.installations())
                .thenReturn(List.of(
                        new EInvoicingService.InstallationDetail(bound, environment, true),
                        new EInvoicingService.InstallationDetail(unbound, environment, false)));

        List<CommercialEInvoicingController.EInvoicingAccountView> views =
                Objects.requireNonNull(controller.installations().getBody());

        assertThat(views).hasSize(2);
        assertThat(views.get(0).connected()).isTrue();
        assertThat(views.get(0).secretBound()).isTrue();
        assertThat(views.get(0).missing()).isEmpty();
        assertThat(views.get(0).baseUrl()).isEqualTo("https://api-partners.didox.uz");
        assertThat(views.get(0).production()).isTrue();
        assertThat(views.get(1).connected()).isFalse();
        assertThat(views.get(1).secretBound()).isFalse();
        assertThat(views.get(1).missing()).containsExactly("SECRET_REFERENCE", "SELLER_TAXPAYER_NUMBER", "SELLER_NAME");
        assertThat(views.get(1).adapterWired()).isFalse();
        assertThat(JsonMapper.builder().build().writeValueAsString(views))
                .as("not in the response body, in any field")
                .doesNotContain("operator-secret-ref")
                .doesNotContain(REFERENCE);
    }

    @Test
    @DisplayName("sending hands the service the statement, the operator, the chosen company, the reason and the caller")
    void sendingPassesWhatWasAsked() {
        StatementEInvoice sent = einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.DRAFT);
        when(service.send(any(), any(), anyString())).thenReturn(sent);
        UUID company = UUID.randomUUID();

        ResponseEntity<CommercialEInvoicingController.EInvoiceView> response = controller.send(
                TENANT,
                STATEMENT,
                new CommercialEInvoicingController.EInvoiceSendRequest("DIDOX", company, "month closed"));

        ArgumentCaptor<EInvoicingService.SendRequest> request =
                ArgumentCaptor.forClass(EInvoicingService.SendRequest.class);
        ArgumentCaptor<ActorRef> actor = ArgumentCaptor.forClass(ActorRef.class);
        verify(service).send(request.capture(), actor.capture(), anyString());
        assertThat(request.getValue())
                .isEqualTo(new EInvoicingService.SendRequest(TENANT, STATEMENT, "DIDOX", company, "month closed"));
        assertThat(actor.getValue().subject()).isEqualTo("finance.staff");

        CommercialEInvoicingController.EInvoiceView view = Objects.requireNonNull(response.getBody());
        assertThat(view.einvoiceId()).isEqualTo(sent.id());
        assertThat(view.provider()).isEqualTo("DIDOX");
        assertThat(view.delivery()).isEqualTo("SUBMITTED");
        assertThat(view.operatorState()).isEqualTo("DRAFT");
        assertThat(view.operatorDocumentId()).isEqualTo("DOC-1");
        assertThat(view.net().amountMinor()).isEqualTo(95_000_000);
        assertThat(view.vat().amountMinor()).isEqualTo(11_400_000);
        assertThat(view.total().amountMinor()).isEqualTo(106_400_000);
        assertThat(view.total().currency()).isEqualTo("UZS");
        assertThat(view.live()).isTrue();
        assertThat(view.lines()).as("a summary carries no lines").isNull();
    }

    @Test
    @DisplayName("the detail read carries the document that was sent, line by line")
    void theDetailCarriesTheLines() {
        StatementEInvoice sent = einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.SENT);
        when(service.find(TENANT, sent.id())).thenReturn(sent);

        CommercialEInvoicingController.EInvoiceView view =
                Objects.requireNonNull(controller.one(TENANT, sent.id()).getBody());

        assertThat(view.lines()).hasSize(1);
        CommercialEInvoicingController.EInvoiceLineView line =
                Objects.requireNonNull(view.lines()).getFirst();
        assertThat(line.name()).isEqualTo("Подписка");
        assertThat(line.net().amountMinor()).isEqualTo(95_000_000);
        assertThat(line.vatRateBp()).isEqualTo(1200);
        assertThat(line.gross().amountMinor()).isEqualTo(106_400_000);
    }

    @Test
    @DisplayName("a refresh says why nothing could be learned when the operator did not answer")
    void aRefreshReportsAnUnavailableOperator() {
        StatementEInvoice sent = einvoice(EInvoiceDelivery.UNCERTAIN, null);
        when(service.refresh(eq(TENANT), eq(sent.id()), any(), anyString()))
                .thenReturn(new EInvoicingService.Refreshed(sent, "PROVIDER_UNAVAILABLE"));

        CommercialEInvoicingController.EInvoiceRefreshView view =
                Objects.requireNonNull(controller.refresh(TENANT, sent.id()).getBody());

        assertThat(view.unavailableCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(view.einvoice().delivery()).isEqualTo("UNCERTAIN");
        assertThat(view.einvoice().operatorState()).isNull();
    }

    @Test
    @DisplayName("an account is updated under the version that was read, and its settings go to the service whole")
    void anAccountIsUpdatedUnderAVersion() {
        EInvoicingInstallation updated = installation("DRAFT", REFERENCE);
        when(service.updateInstallation(any(), anyLong(), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenReturn(updated);
        when(service.installationDetail(INSTALLATION))
                .thenReturn(new EInvoicingService.InstallationDetail(updated, null, false));

        ResponseEntity<CommercialEInvoicingController.EInvoicingAccountView> response = controller.update(
                INSTALLATION,
                new CommercialEInvoicingController.EInvoicingAccountUpdate(
                        "Didox", REFERENCE, Map.of("sellerName", "HorecaOS MCHJ"), "opened"),
                ifMatch(3));
        CommercialEInvoicingController.EInvoicingAccountView view = Objects.requireNonNull(response.getBody());
        assertThat(response.getHeaders().getETag()).isEqualTo("W/\"2\"");

        verify(service)
                .updateInstallation(
                        eq(INSTALLATION),
                        eq(3L),
                        eq("Didox"),
                        eq(REFERENCE),
                        eq(Map.of("sellerName", "HorecaOS MCHJ")),
                        any(),
                        eq("opened"),
                        anyString());
        assertThat(view.secretBound()).isTrue();
        assertThat(view.baseUrl())
                .as("an environment that cannot be found is said so, not guessed")
                .isNull();
    }

    @Test
    @DisplayName("a classification is not confirmed unless the request says so")
    void aClassificationIsNotConfirmedByDefault() {
        EInvoicingLineClassification row = new EInvoicingLineClassification(
                "PLAN",
                "Подписка",
                "1030",
                "Услуги",
                "1500002",
                "услуга",
                1200,
                true,
                null,
                null,
                1,
                "x",
                Instant.now());
        when(service.updateClassification(
                        anyString(),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any(),
                        anyString(),
                        anyString()))
                .thenReturn(row);

        CommercialEInvoicingController.EInvoicingClassificationView view = Objects.requireNonNull(controller
                .classify(
                        "PLAN",
                        new CommercialEInvoicingController.EInvoicingClassificationUpdate(
                                "Подписка", "1030", "Услуги", "1500002", "услуга", 1200, null, "edited"),
                        ifMatch(0))
                .getBody());

        verify(service)
                .updateClassification(
                        eq("PLAN"),
                        eq(0L),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(1200),
                        eq(false),
                        any(),
                        eq("edited"),
                        anyString());
        assertThat(view.provisional()).isTrue();
        assertThat(view.confirmedBy()).isNull();
    }

    @Test
    @DisplayName(
            "a versioned change with no If-Match is refused before the service is asked, so a blind write is impossible")
    void aVersionedChangeNeedsIfMatch() {
        MockHttpServletRequest bare = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.update(
                        INSTALLATION,
                        new CommercialEInvoicingController.EInvoicingAccountUpdate("Didox", null, Map.of(), "r"),
                        bare))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refusal -> assertThat(refusal.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> controller.activate(
                        INSTALLATION, new CommercialEInvoicingController.EInvoicingReasonRequest("r"), bare))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.suspend(
                        INSTALLATION, new CommercialEInvoicingController.EInvoicingReasonRequest("r"), bare))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.classify(
                        "PLAN",
                        new CommercialEInvoicingController.EInvoicingClassificationUpdate(
                                "a", "b", "c", "d", "e", 1200, false, "r"),
                        bare))
                .isInstanceOf(ApiException.class);
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @DisplayName("activating and suspending read the version from If-Match and answer the account as it now stands")
    void activatingAndSuspending() {
        EInvoicingInstallation live = installation("ACTIVE", REFERENCE);
        when(service.activateInstallation(eq(INSTALLATION), eq(5L), any(), eq("go live"), anyString()))
                .thenReturn(live);
        when(service.suspendInstallation(eq(INSTALLATION), eq(6L), any(), eq("outage"), anyString()))
                .thenReturn(live);
        when(service.installationDetail(INSTALLATION))
                .thenReturn(new EInvoicingService.InstallationDetail(live, null, true));

        ResponseEntity<CommercialEInvoicingController.EInvoicingAccountView> activated = controller.activate(
                INSTALLATION, new CommercialEInvoicingController.EInvoicingReasonRequest("go live"), ifMatch(5));
        controller.suspend(
                INSTALLATION, new CommercialEInvoicingController.EInvoicingReasonRequest("outage"), ifMatch(6));

        assertThat(Objects.requireNonNull(activated.getBody()).connected()).isTrue();
        verify(service).activateInstallation(eq(INSTALLATION), eq(5L), any(), eq("go live"), anyString());
        verify(service).suspendInstallation(eq(INSTALLATION), eq(6L), any(), eq("outage"), anyString());
    }

    private static MockHttpServletRequest ifMatch(long version) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-Match", "W/\"" + version + "\"");
        return request;
    }

    // ----------------------------------------------------------------- fixtures

    private static EInvoicingInstallation installation(String status, @Nullable String reference) {
        return new EInvoicingInstallation(
                INSTALLATION,
                "DIDOX",
                "didox_production",
                "Didox",
                status,
                reference,
                reference == null
                        ? Map.of()
                        : Map.of("sellerTaxpayerNumber", "305000001", "sellerName", "HorecaOS MCHJ"),
                "didox-partner-api-v1",
                2,
                "finance.staff",
                Instant.parse("2026-10-07T09:00:00Z"));
    }

    private static StatementEInvoice einvoice(EInvoiceDelivery delivery, @Nullable EInvoiceOperatorState state) {
        UUID id = UUID.randomUUID();
        EInvoiceDocument.Line line = new EInvoiceDocument.Line(
                1,
                "Подписка",
                "1030",
                "Услуги",
                "1500002",
                "услуга",
                1,
                95_000_000,
                95_000_000,
                1200,
                11_400_000,
                106_400_000);
        EInvoiceDocument document = new EInvoiceDocument(
                id.toString().replace("-", ""),
                "S-2026-09-000001",
                LocalDate.parse("2026-10-07"),
                "UZS",
                new EInvoiceDocument.Party("305000001", "HorecaOS MCHJ", null, null, null, null, null),
                new EInvoiceDocument.Party("301234567", "Non uyi MCHJ", null, null, null, null, null),
                "S-2026-09-000001",
                LocalDate.parse("2026-10-01"),
                true,
                List.of(line));
        Instant now = Instant.parse("2026-10-07T09:00:00Z");
        return new StatementEInvoice(
                id,
                TENANT,
                STATEMENT,
                INSTALLATION,
                "DIDOX",
                UUID.randomUUID(),
                "301234567",
                "Non uyi MCHJ",
                "305000001",
                "S-2026-09-000001",
                LocalDate.parse("2026-10-07"),
                "UZS",
                95_000_000,
                11_400_000,
                106_400_000,
                true,
                document,
                delivery,
                null,
                null,
                delivery == EInvoiceDelivery.SUBMITTED ? "DOC-1" : null,
                state,
                state == null ? null : "created",
                now,
                now,
                "month closed",
                "finance.staff",
                now,
                now,
                1);
    }
}
