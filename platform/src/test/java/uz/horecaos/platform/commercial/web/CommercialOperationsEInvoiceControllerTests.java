package uz.horecaos.platform.commercial.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.application.EInvoicingService;
import uz.horecaos.platform.commercial.domain.EInvoiceDelivery;
import uz.horecaos.platform.commercial.domain.StatementEInvoice;

/**
 * What the merchant is told about the e-invoice of a statement (ADR 0096, IA 8.6): the operator's word for where it
 * stands, whether it waits for their signature, and nothing that belongs to staff.
 *
 * <p>Authorization is not asserted here: {@code EndpointCapabilityDeclarationTests} scans every endpoint for exactly
 * one declaration.
 */
class CommercialOperationsEInvoiceControllerTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-6000-7000-8000-0000000000a1");
    private static final UUID STATEMENT = UUID.fromString("018f9c10-6000-7000-8000-0000000000b1");
    private static final UUID INSTALLATION = UUID.fromString("018f9c10-5000-7000-8000-0000000000d1");

    private final EInvoicingService service = mock(EInvoicingService.class);
    private final CommercialOperationsEInvoiceController controller =
            new CommercialOperationsEInvoiceController(service);

    @Test
    @DisplayName("a statement the operator holds signed by HorecaOS is waiting for the merchant to sign")
    void aSentInvoiceAwaitsTheMerchantsSignature() {
        when(service.forTenant(TENANT))
                .thenReturn(List.of(einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.SENT, "sent to buyer")));

        List<CommercialOperationsEInvoiceController.StatementEInvoiceView> views =
                Objects.requireNonNull(controller.einvoices(TENANT).getBody());

        verify(service).forTenant(TENANT);
        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.statementId()).isEqualTo(STATEMENT);
            assertThat(view.provider()).isEqualTo("DIDOX");
            assertThat(view.state()).isEqualTo("SENT");
            assertThat(view.awaitingYourSignature()).isTrue();
            assertThat(view.operatorStatus()).as("the operator's own words").isEqualTo("sent to buyer");
            assertThat(view.current()).isTrue();
            assertThat(view.total().amountMinor()).isEqualTo(106_400_000);
            assertThat(view.total().currency()).isEqualTo("UZS");
            assertThat(view.documentNumber()).isEqualTo("S-2026-09-000001");
        });
    }

    @Test
    @DisplayName("only SENT waits for the merchant: a draft is HorecaOS's to sign, a signed one is done")
    void onlySentWaitsForTheMerchant() {
        when(service.forTenant(TENANT))
                .thenReturn(List.of(
                        einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.DRAFT, "draft"),
                        einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.SIGNED, "signed"),
                        einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.REFUSED, "refused")));

        var views = Objects.requireNonNull(controller.einvoices(TENANT).getBody());

        assertThat(views)
                .extracting(
                        CommercialOperationsEInvoiceController.StatementEInvoiceView::state,
                        CommercialOperationsEInvoiceController.StatementEInvoiceView::awaitingYourSignature,
                        CommercialOperationsEInvoiceController.StatementEInvoiceView::current)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DRAFT", false, true),
                        org.assertj.core.groups.Tuple.tuple("SIGNED", false, true),
                        org.assertj.core.groups.Tuple.tuple("REFUSED", false, false));
    }

    @Test
    @DisplayName(
            "an attempt that may have reached the operator and has no answer is shown as unconfirmed, not hidden and not guessed")
    void anUncertainAttemptIsUnconfirmed() {
        when(service.forTenant(TENANT)).thenReturn(List.of(einvoice(EInvoiceDelivery.UNCERTAIN, null, null)));

        var views = Objects.requireNonNull(controller.einvoices(TENANT).getBody());

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.state()).isEqualTo("UNCONFIRMED");
            assertThat(view.awaitingYourSignature()).isFalse();
            assertThat(view.operatorStatus()).isNull();
        });
    }

    @Test
    @DisplayName("an attempt that failed, or is still being sent, is not an invoice the merchant has")
    void failedAndPendingAttemptsAreNotShown() {
        when(service.forTenant(TENANT))
                .thenReturn(List.of(
                        einvoice(EInvoiceDelivery.FAILED, null, null),
                        einvoice(EInvoiceDelivery.PENDING, null, null),
                        einvoice(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.UNKNOWN, "a status nobody mapped")));

        var views = Objects.requireNonNull(controller.einvoices(TENANT).getBody());

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.state()).isEqualTo("UNKNOWN");
            assertThat(view.operatorStatus()).isEqualTo("a status nobody mapped");
        });
    }

    @Test
    @DisplayName("nothing that belongs to staff or to the document's internals is in the response, in any field")
    void noStaffDetailIsInTheResponse() {
        StatementEInvoice failedDetail = new StatementEInvoiceBuilder()
                .failure("OPERATOR_REJECTED_DOCUMENT", "Buyer 301234567890123 is not registered")
                .build(EInvoiceDelivery.SUBMITTED, EInvoiceOperatorState.SENT, "sent");
        when(service.forTenant(TENANT)).thenReturn(List.of(failedDetail));

        String body = JsonMapper.builder()
                .build()
                .writeValueAsString(
                        Objects.requireNonNull(controller.einvoices(TENANT).getBody()));

        assertThat(body)
                .doesNotContain("finance.staff")
                .doesNotContain("month closed")
                .doesNotContain("DOC-1")
                .doesNotContain("305000001")
                .doesNotContain("301234567")
                .doesNotContain("OPERATOR_REJECTED_DOCUMENT")
                .doesNotContain("is not registered");
    }

    private static StatementEInvoice einvoice(
            EInvoiceDelivery delivery, @Nullable EInvoiceOperatorState state, @Nullable String status) {
        return new StatementEInvoiceBuilder().build(delivery, state, status);
    }

    /** One fixture, with the staff-only fields filled so a test can prove they never leave. */
    private static final class StatementEInvoiceBuilder {

        private @Nullable String failureCode;
        private @Nullable String failureDetail;

        StatementEInvoiceBuilder failure(String code, String detail) {
            this.failureCode = code;
            this.failureDetail = detail;
            return this;
        }

        StatementEInvoice build(
                EInvoiceDelivery delivery, @Nullable EInvoiceOperatorState state, @Nullable String status) {
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
                    failureCode,
                    failureDetail,
                    delivery == EInvoiceDelivery.SUBMITTED ? "DOC-1" : null,
                    state,
                    status,
                    now,
                    now,
                    "month closed",
                    "finance.staff",
                    now,
                    now,
                    1);
        }
    }
}
