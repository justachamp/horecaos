package uz.horecaos.platform.integration.camel.einvoicing.faktura;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.commercial.api.EInvoiceDocument;
import uz.horecaos.platform.commercial.api.EInvoiceDocumentReference;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorAccount;
import uz.horecaos.platform.commercial.api.EInvoiceOperatorState;
import uz.horecaos.platform.commercial.api.EInvoiceSendOutcome;
import uz.horecaos.platform.commercial.api.EInvoiceStateOutcome;
import uz.horecaos.platform.commercial.api.EInvoicingOperator;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingApiCall;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingApiCall.Request;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingApiTransport;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingCredentials;
import uz.horecaos.platform.integration.camel.einvoicing.EInvoicingOutcomes;

/**
 * Faktura.uz, behind ADR 0096's port, from the operator's published Swagger document
 * ({@code https://api.faktura.uz/swagger}, transcribed in {@code docs/providers/faktura-uz.md}).
 *
 * <p>This is the better documented of the two operators, and what it documents is used as
 * written: {@code POST https://account.faktura.uz/token} (OAuth password grant, form-encoded)
 * gives a bearer valid for 168 hours; {@code POST /Api/Document/ImportDocumentRegister} creates
 * documents from a {@code document_container} whose {@code invoices} carry our own identifier in
 * {@code id}; {@code POST /Api/GetDocumentStatus}, {@code GET /Api/Document/GetDetails/{uid}}
 * and {@code GET /Api/Document/GetDocumentByClientDocumentUid} read them back.
 *
 * <p><strong>What is not documented</strong> is the table of status codes: it is served by an
 * authenticated {@code GET /Api/Document/GetDocumentStatuses} and only two of its rows
 * ("Archived", "ArchiveCanceled") are named in the Swagger text. So the state is read from the
 * three booleans {@code GetDetails} documents ({@code isSent}, {@code isOwnerSigned}, {@code
 * isContractorSigned}), and the status text is consulted only to recognise a refusal or a
 * cancellation; the operator's own words are kept beside the reading on the record.
 *
 * <p><strong>A create is not repeated.</strong> The container's {@code id} is our identifier and
 * is what resolves a lost answer ({@code GetDocumentByClientDocumentUid}), but no deduplication on
 * it is documented, so a lost answer is {@code Uncertain} and is asked about, never re-sent.
 */
@Component
public class FakturaEInvoicingOperator implements EInvoicingOperator {

    /** The ADR 0026 provider type a platform installation must declare to be called here. */
    public static final String PROVIDER_TYPE = "FAKTURA_UZ";

    static final String ADAPTER_VERSION = "faktura-api-v1";

    /** A token lasts 168 hours; refreshed a day early. */
    static final Duration TOKEN = Duration.ofHours(144);

    /** Faktura's own origin code for a service (1 own production, 2 resale, 3 service, 4 none). */
    static final String ORIGIN_SERVICE = "3";

    private static final Set<String> REFUSED_STEMS = Set.of("отклон", "отказ", "reject", "declin", "refus");
    private static final Set<String> CANCELLED_STEMS = Set.of("аннул", "отмен", "cancel");

    private final EInvoicingApiTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentMap<UUID, Token> tokens = new ConcurrentHashMap<>();

    public FakturaEInvoicingOperator(EInvoicingApiTransport transport, ObjectMapper mapper, Clock clock) {
        this.transport = transport;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String adapterVersion() {
        return ADAPTER_VERSION;
    }

    // ------------------------------------------------------------------- send

    @Override
    public EInvoiceSendOutcome send(EInvoiceOperatorAccount account, EInvoiceDocument document) {
        String sellerTin = account.config().get("sellerTaxpayerNumber");
        if (sellerTin == null || sellerTin.isBlank()) {
            return new EInvoiceSendOutcome.NotSent(
                    "OPERATOR_NOT_CONFIGURED", "The seller's taxpayer number is not set");
        }
        Map<String, Object> container;
        try {
            container = Map.of("invoices", List.of(invoice(document)));
        } catch (RuntimeException unmappable) {
            return new EInvoiceSendOutcome.Refused(
                    "DOCUMENT_UNMAPPABLE", unmappable.getClass().getSimpleName());
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            Login login = token(account, document.clientReference());
            if (login.failure() != null) {
                return login.failure();
            }
            ProviderOutcome outcome = transport.exchange(call(
                    account,
                    account.baseUrl(),
                    "create",
                    "POST",
                    "/Api/Document/ImportDocumentRegister?companyInn=" + encode(sellerTin),
                    document.clientReference(),
                    secret -> Request.json(bearer(login.token()), container),
                    Set.of()));
            if (outcome.status() == ProviderOutcome.Status.REJECTED
                    && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode())
                    && attempt == 0) {
                // The bearer was refused (expired early or revoked): Faktura rejects before it
                // acts, so signing in again and repeating is safe. Only once.
                tokens.remove(account.installationId());
                continue;
            }
            if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
                return EInvoicingOutcomes.sendFailure(outcome);
            }
            return imported(outcome.normalized());
        }
        return new EInvoiceSendOutcome.NotSent("OPERATOR_AUTHENTICATION", "Faktura.uz refused the token twice");
    }

    /**
     * {@code ImportDocumentRegister}'s answer: counts, and an item per document with the
     * operator's {@code UniqueId} (or, for a failure, its {@code ResultCode} and {@code Message}).
     * A success count with no identifier is not believed: nothing could be asked about it later.
     */
    private static EInvoiceSendOutcome imported(Map<String, Object> body) {
        List<Object> successes = EInvoicingOutcomes.list(body.get("SuccessItems"));
        for (Object item : successes) {
            String uniqueId = EInvoicingOutcomes.text(EInvoicingOutcomes.map(item), List.of("UniqueId"));
            if (uniqueId != null) {
                return new EInvoiceSendOutcome.Accepted(uniqueId, EInvoiceOperatorState.DRAFT, "imported");
            }
        }
        List<Object> errors = EInvoicingOutcomes.list(body.get("ErrorItems"));
        if (!errors.isEmpty()
                || Integer.valueOf(0).compareTo(orZero(EInvoicingOutcomes.integer(body, List.of("ErrorCount")))) < 0) {
            List<String> reasons = new ArrayList<>();
            for (Object item : errors) {
                Map<String, Object> error = EInvoicingOutcomes.map(item);
                Integer code = EInvoicingOutcomes.integer(error, List.of("ResultCode"));
                reasons.add((code == null ? "" : code + ": ")
                        + EInvoicingOutcomes.scrub(EInvoicingOutcomes.text(error, List.of("Message"))));
            }
            return new EInvoiceSendOutcome.Refused(
                    "OPERATOR_REJECTED_DOCUMENT",
                    reasons.isEmpty() ? "Faktura.uz refused the document" : String.join("; ", reasons));
        }
        return new EInvoiceSendOutcome.Uncertain(
                "IMPORT_UNREADABLE", "Faktura.uz answered the import with neither a document nor an error");
    }

    private static Integer orZero(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ state

    @Override
    public EInvoiceStateOutcome state(EInvoiceOperatorAccount account, EInvoiceDocumentReference reference) {
        String sellerTin = account.config().get("sellerTaxpayerNumber");
        if (sellerTin == null || sellerTin.isBlank()) {
            return new EInvoiceStateOutcome.Unavailable(
                    "OPERATOR_NOT_CONFIGURED", "The seller's taxpayer number is not set");
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            Login login = token(account, reference.clientReference());
            if (login.failure() != null) {
                return new EInvoiceStateOutcome.Unavailable("OPERATOR_LOGIN_FAILED", detail(login.failure()));
            }
            EInvoiceStateOutcome answer = ask(account, reference, sellerTin, login.token());
            if (answer instanceof EInvoiceStateOutcome.Unavailable unavailable
                    && "PROVIDER_AUTHENTICATION".equals(unavailable.code())
                    && attempt == 0) {
                tokens.remove(account.installationId());
                continue;
            }
            return answer;
        }
        return new EInvoiceStateOutcome.Unavailable("OPERATOR_AUTHENTICATION", "Faktura.uz refused the token twice");
    }

    private EInvoiceStateOutcome ask(
            EInvoiceOperatorAccount account,
            EInvoiceDocumentReference reference,
            String sellerTin,
            @Nullable String token) {
        String uniqueId = reference.operatorDocumentId();
        if (uniqueId == null) {
            ProviderOutcome found = transport.exchange(call(
                    account,
                    account.baseUrl(),
                    "find",
                    "GET",
                    "/Api/Document/GetDocumentByClientDocumentUid?clientId=" + encode(reference.clientReference())
                            + "&companyInn=" + encode(sellerTin),
                    reference.clientReference(),
                    secret -> Request.of(bearer(token)),
                    Set.of(404)));
            if (found.status() != ProviderOutcome.Status.SUCCESS) {
                return unavailable(found);
            }
            if (Integer.valueOf(404).equals(found.normalized().get(ProviderHttpClient.STATUS_KEY))) {
                return new EInvoiceStateOutcome.NotFound();
            }
            uniqueId = EInvoicingOutcomes.text(found.normalized(), List.of("UniqueId"));
            if (uniqueId == null) {
                return new EInvoiceStateOutcome.Unavailable(
                        "STATE_UNREADABLE", "Faktura.uz found the client reference but named no document");
            }
        }
        String id = uniqueId;

        ProviderOutcome status = transport.exchange(call(
                account,
                account.baseUrl(),
                "status",
                "POST",
                "/Api/GetDocumentStatus?companyInn=" + encode(sellerTin),
                reference.clientReference(),
                secret -> Request.json(bearer(token), Map.of("DocumentUniqueIds", List.of(id))),
                Set.of()));
        if (status.status() != ProviderOutcome.Status.SUCCESS) {
            return unavailable(status);
        }
        Map<String, Object> statusBody = status.normalized();
        Map<String, Object> entry = firstStatus(statusBody);
        if (entry == null) {
            // Success: false with "IncorrectDocumentUid" (1007) is the operator saying it holds no such document.
            if (hasError(statusBody, 1007)) {
                return new EInvoiceStateOutcome.NotFound();
            }
            return new EInvoiceStateOutcome.Unavailable(
                    "STATE_UNREADABLE", "Faktura.uz answered the status without an entry for the document");
        }
        Integer code = EInvoicingOutcomes.integer(entry, List.of("Status"));
        String description = EInvoicingOutcomes.text(entry, List.of("StatusDescription"));

        ProviderOutcome details = transport.exchange(call(
                account,
                account.baseUrl(),
                "get",
                "GET",
                "/Api/Document/GetDetails/" + encode(id) + "?companyInn=" + encode(sellerTin),
                reference.clientReference(),
                secret -> Request.of(bearer(token)),
                Set.of(404)));
        if (details.status() != ProviderOutcome.Status.SUCCESS) {
            return unavailable(details);
        }
        Map<String, Object> detailBody = details.normalized();
        if (Integer.valueOf(404).equals(detailBody.get(ProviderHttpClient.STATUS_KEY))) {
            return new EInvoiceStateOutcome.NotFound();
        }
        EInvoiceOperatorState state = stateOf(
                description,
                Boolean.TRUE.equals(detailBody.get("isSent")),
                Boolean.TRUE.equals(detailBody.get("isContractorSigned")));
        return new EInvoiceStateOutcome.Known(id, state, raw(code, description));
    }

    /**
     * A document's state from what Faktura documents: the booleans say how far it got, and the
     * status text, which is the only place a refusal or a cancellation is named, overrides them.
     */
    static EInvoiceOperatorState stateOf(@Nullable String statusText, boolean sent, boolean contractorSigned) {
        String text = statusText == null ? "" : statusText.toLowerCase(Locale.ROOT);
        if (REFUSED_STEMS.stream().anyMatch(text::contains)) {
            return EInvoiceOperatorState.REFUSED;
        }
        if (CANCELLED_STEMS.stream().anyMatch(text::contains)) {
            return EInvoiceOperatorState.CANCELLED;
        }
        if (contractorSigned) {
            return EInvoiceOperatorState.SIGNED;
        }
        return sent ? EInvoiceOperatorState.SENT : EInvoiceOperatorState.DRAFT;
    }

    private static String raw(@Nullable Integer code, @Nullable String description) {
        String text = (code == null ? "" : code + ":") + EInvoicingOutcomes.scrub(description);
        return text.isEmpty() ? "none" : text;
    }

    private static @Nullable Map<String, Object> firstStatus(Map<String, Object> body) {
        Map<String, Object> data = EInvoicingOutcomes.map(body.get("Data"));
        if (data == null) {
            return null;
        }
        for (Object item : EInvoicingOutcomes.list(data.get("DocumentStatuses"))) {
            Map<String, Object> entry = EInvoicingOutcomes.map(item);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static boolean hasError(Map<String, Object> body, int code) {
        for (Object item : EInvoicingOutcomes.list(body.get("Errors"))) {
            Integer found = EInvoicingOutcomes.integer(EInvoicingOutcomes.map(item), List.of("Code"));
            if (found != null && found == code) {
                return true;
            }
        }
        return false;
    }

    private static EInvoiceStateOutcome unavailable(ProviderOutcome outcome) {
        return new EInvoiceStateOutcome.Unavailable(
                outcome.errorCode() == null ? "UNKNOWN" : outcome.errorCode(),
                outcome.detail() == null ? "" : outcome.detail());
    }

    // ----------------------------------------------------------------- token

    private record Token(String value, Instant expiresAt) {}

    private record Login(@Nullable String token, @Nullable EInvoiceSendOutcome failure) {}

    /**
     * A bearer, fetched from the operator's account host when there is none. Signing in writes
     * nothing at the operator, so a failure is always "not sent".
     */
    private Login token(EInvoiceOperatorAccount account, String correlationId) {
        Token cached = tokens.get(account.installationId());
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return new Login(cached.value(), null);
        }
        ProviderOutcome outcome = transport.exchange(call(
                account,
                accountHost(account.baseUrl()),
                "token",
                "POST",
                "/token",
                correlationId,
                secret -> {
                    EInvoicingCredentials login = EInvoicingCredentials.parse(mapper, secret);
                    Map<String, String> form = new LinkedHashMap<>();
                    form.put("grant_type", "password");
                    form.put("username", login.required("username"));
                    form.put("password", login.required("password"));
                    form.put("client_id", login.required("clientId"));
                    form.put("client_secret", login.required("clientSecret"));
                    return Request.form(Map.of(), form);
                },
                Set.of()));
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            String code = outcome.status() == ProviderOutcome.Status.REJECTED
                            && ("PROVIDER_REJECTED".equals(outcome.errorCode())
                                    || "PROVIDER_AUTHENTICATION".equals(outcome.errorCode()))
                    ? "OPERATOR_LOGIN_REFUSED"
                    : outcome.errorCode() == null ? "OPERATOR_UNAVAILABLE" : outcome.errorCode();
            return new Login(
                    null, new EInvoiceSendOutcome.NotSent(code, outcome.detail() == null ? "" : outcome.detail()));
        }
        String value = EInvoicingOutcomes.text(outcome.normalized(), List.of("access_token"));
        if (value == null) {
            return new Login(
                    null,
                    new EInvoiceSendOutcome.NotSent(
                            "OPERATOR_LOGIN_UNREADABLE", "Faktura.uz's token answer carries no access token"));
        }
        Integer seconds = EInvoicingOutcomes.integer(outcome.normalized(), List.of("expires_in"));
        Duration lifetime =
                seconds == null ? TOKEN : Duration.ofSeconds(seconds).minusHours(24);
        if (lifetime.isNegative() || lifetime.isZero()) {
            lifetime = Duration.ofMinutes(5);
        }
        tokens.put(account.installationId(), new Token(value, clock.instant().plus(lifetime)));
        return new Login(value, null);
    }

    /** The login host: Faktura.uz serves {@code /token} from {@code account.}, not from {@code api.}. */
    static String accountHost(String apiBaseUrl) {
        URI uri = URI.create(apiBaseUrl);
        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).startsWith("api.")) {
            return apiBaseUrl;
        }
        return uri.getScheme() + "://account." + host.substring("api.".length())
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    }

    private static Map<String, String> bearer(@Nullable String token) {
        return Map.of("Authorization", "Bearer " + Objects.requireNonNull(token));
    }

    private static EInvoicingApiCall call(
            EInvoiceOperatorAccount account,
            String baseUrl,
            String operation,
            String method,
            String path,
            String correlationId,
            Function<String, Request> request,
            Set<Integer> accepted) {
        return new EInvoicingApiCall(
                account.installationId(),
                PROVIDER_TYPE,
                account.environmentCode(),
                operation,
                method,
                baseUrl,
                path,
                account.secretReference(),
                request,
                accepted,
                correlationId,
                null);
    }

    // --------------------------------------------------------------- mapping

    /**
     * One invoice as the {@code document_container}'s {@code invoices} item describes it: {@code
     * head} (who sends to whom), {@code document} (the numbers and the lines) and {@code id}
     * (ours). Amounts are decimal strings, as every amount in that model is.
     */
    static Map<String, Object> invoice(EInvoiceDocument document) {
        String currency = document.currency();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put(
                "file_name",
                "Invoice_" + document.seller().taxpayerNumber() + "_"
                        + document.buyer().taxpayerNumber() + "_"
                        + document.documentDate().toString().replace("-", ""));
        head.put("program_version", "1.0.0");
        head.put("format_version", "1.0.0");
        head.put("sender", Map.of("sender_info", party(document.seller())));
        head.put("receiver", Map.of("receiver_info", party(document.buyer())));

        List<Map<String, Object>> items = new ArrayList<>();
        for (EInvoiceDocument.Line line : document.lines()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item_number", Integer.toString(line.number()));
            item.put("description", line.name());
            item.put("volume", Long.toString(line.quantity()));
            item.put("measurement_unit", line.packageName());
            item.put("measurement_unit_code", line.packageCode());
            item.put("unit_price", money(line.unitPriceMinor(), currency));
            item.put("subtotal", money(line.netMinor(), currency));
            item.put("excise", Map.of("excise_rate", "0", "excise_value", "0.00"));
            item.put("vat", Map.of("vat_rate", line.vatPercentText(), "vat_value", money(line.vatMinor(), currency)));
            item.put("subtotal_with_vat", money(line.grossMinor(), currency));
            item.put("subtotal_with_taxes", money(line.grossMinor(), currency));
            item.put("catalog", Map.of("code", line.classificationCode(), "name", line.classificationName()));
            item.put("origin", ORIGIN_SERVICE);
            items.add(item);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("column_subtotal", money(document.netMinor(), currency));
        summary.put("column_excise_value", "0.00");
        summary.put("column_vat_value", money(document.vatMinor(), currency));
        summary.put("column_subtotal_with_taxes_total", money(document.grossMinor(), currency));

        Map<String, Object> inWords = new LinkedHashMap<>();
        inWords.put(
                "column_subtotal_in_words",
                RussianAmountInWords.sums(EInvoiceDocument.decimal(document.netMinor(), currency)));
        inWords.put(
                "column_subtotal_with_taxes_in_words",
                RussianAmountInWords.sums(EInvoiceDocument.decimal(document.grossMinor(), currency)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("document_number", document.documentNumber());
        body.put("document_date", document.documentDate().toString());
        body.put("contract_number", document.contractNumber());
        body.put("contract_date", document.contractDate().toString());
        body.put("customer_system_id", document.clientReference());
        body.put("items", items);
        body.put("column_summary_values", summary);
        body.put("column_summary_values_in_words", inWords);

        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("id", document.clientReference());
        invoice.put("head", head);
        invoice.put("document", body);
        return invoice;
    }

    private static Map<String, Object> party(EInvoiceDocument.Party party) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("INN", party.taxpayerNumber());
        out.put("company_name", party.name());
        putIfPresent(out, "company_vat_code", party.vatRegistrationCode());
        if (party.address() != null && !party.address().isBlank()) {
            // Faktura wants an address as parts; the platform holds one line, and the street
            // field is where a one-line address is least wrong.
            out.put("address", Map.of("street", party.address()));
        }
        Map<String, Object> bank = new LinkedHashMap<>();
        putIfPresent(bank, "account_number", party.bankAccount());
        putIfPresent(bank, "bank_code", party.bankCode());
        putIfPresent(bank, "bank_name", party.bankName());
        if (!bank.isEmpty()) {
            out.put("bank_details", bank);
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String money(long minor, String currency) {
        BigDecimal amount = EInvoiceDocument.decimal(minor, currency);
        return amount.toPlainString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String detail(EInvoiceSendOutcome failure) {
        return switch (failure) {
            case EInvoiceSendOutcome.NotSent notSent -> notSent.code();
            case EInvoiceSendOutcome.Refused refused -> refused.code();
            case EInvoiceSendOutcome.Uncertain uncertain -> uncertain.code();
            case EInvoiceSendOutcome.Accepted accepted -> "accepted";
        };
    }
}
