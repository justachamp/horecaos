package uz.horecaos.platform.integration.camel.einvoicing.didox;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Didox, behind ADR 0096's port, from {@code docs/providers/didox.md}.
 *
 * <p><strong>What is documented and what is not.</strong> The shape of the calls is the one the
 * operator's partner SDK (npm {@code didox}) makes: {@code POST /v1/auth/{tin}/password/{locale}}
 * with the partner token in {@code Partner-Authorization} returns a session {@code token}, sent
 * as {@code user-key} on everything after it; {@code POST /v1/documents/002/create} makes an
 * invoice draft; {@code GET /v1/documents/{id}} and {@code GET /v2/documents} read it back. The
 * <em>answers</em> are not documented anywhere HorecaOS can read -- it has no account -- so
 * every read of one is tolerant (it looks under the keys the SDK's own types suggest) and an
 * answer it cannot make sense of is {@code UNCERTAIN} or {@code UNKNOWN}, never guessed.
 *
 * <p><strong>A create is not repeated.</strong> Didox documents no idempotency key. A create
 * whose answer is lost is {@code Uncertain}, and the platform resolves it with {@link #state},
 * which looks the invoice up by its number -- the statement's number, unique across the
 * platform. The one thing this class does repeat is the <em>login</em> after an expired session,
 * and only because a refused {@code user-key} means the operator refused before it acted.
 *
 * <p>The session token lives in this object for its life and nowhere else: never logged,
 * never stored, never returned.
 */
@Component
public class DidoxEInvoicingOperator implements EInvoicingOperator {

    /** The ADR 0026 provider type a platform installation must declare to be called here. */
    public static final String PROVIDER_TYPE = "DIDOX";

    static final String ADAPTER_VERSION = "didox-partner-api-v1";

    /** The operator's document type code for an electronic invoice (счёт-фактура). */
    static final String INVOICE_TYPE = "002";

    /** A session lasts 360 minutes at Didox; refreshed an hour early so a long call never straddles its end. */
    static final Duration SESSION = Duration.ofMinutes(300);

    /** How many documents one lookup by number asks for; a full page may have a next one. */
    static final int LIST_LIMIT = 10;

    private static final List<String> ID_KEYS = List.of("_id", "id", "documentId", "document_id", "doc_id", "uuid");
    private static final List<String> STATUS_KEYS = List.of("status", "doc_status", "docStatus", "statusId");
    private static final List<String> NUMBER_KEYS = List.of("name", "number", "docNumber", "doc_number", "FacturaNo");

    private final EInvoicingApiTransport transport;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public DidoxEInvoicingOperator(EInvoicingApiTransport transport, ObjectMapper mapper, Clock clock) {
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
        Map<String, Object> payload;
        try {
            payload = payload(document);
        } catch (RuntimeException unmappable) {
            return new EInvoiceSendOutcome.Refused(
                    "DOCUMENT_UNMAPPABLE", unmappable.getClass().getSimpleName());
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            Login login = session(account, document.clientReference());
            if (login.failure() != null) {
                return login.failure();
            }
            ProviderOutcome outcome = transport.exchange(call(
                    account,
                    "create",
                    "POST",
                    "/v1/documents/" + INVOICE_TYPE + "/create",
                    document.clientReference(),
                    secret -> Request.json(headers(secret, login.token()), payload),
                    Set.of()));
            if (outcome.status() == ProviderOutcome.Status.REJECTED
                    && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode())
                    && attempt == 0) {
                // The session was refused: Didox rejects before it acts, so signing in again
                // and repeating is safe. Only once.
                sessions.remove(account.installationId());
                continue;
            }
            if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
                return EInvoicingOutcomes.sendFailure(outcome);
            }
            return accepted(outcome.normalized());
        }
        return new EInvoiceSendOutcome.NotSent("OPERATOR_AUTHENTICATION", "Didox refused the session twice");
    }

    private static EInvoiceSendOutcome accepted(Map<String, Object> body) {
        String id = documentId(body);
        if (id == null) {
            // Created, said Didox, and named nothing. Whether it exists is for the number-keyed
            // lookup to say, never for a second create.
            return new EInvoiceSendOutcome.Uncertain(
                    "ACCEPTED_WITHOUT_ID", "Didox accepted the document without naming it");
        }
        Integer status = firstInteger(body);
        EInvoiceOperatorState state = status == null ? EInvoiceOperatorState.DRAFT : DidoxStatus.of(status);
        return new EInvoiceSendOutcome.Accepted(id, state, status == null ? "created" : DidoxStatus.raw(status));
    }

    // ------------------------------------------------------------------ state

    @Override
    public EInvoiceStateOutcome state(EInvoiceOperatorAccount account, EInvoiceDocumentReference reference) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Login login = session(account, reference.clientReference());
            if (login.failure() != null) {
                return new EInvoiceStateOutcome.Unavailable("OPERATOR_LOGIN_FAILED", detail(login.failure()));
            }
            boolean byId = reference.operatorDocumentId() != null;
            String path = byId
                    ? "/v1/documents/" + encode(reference.operatorDocumentId())
                    : "/v2/documents?owner=1&doctype=" + INVOICE_TYPE + "&name=" + encode(reference.documentNumber())
                            + "&page=1&limit=" + LIST_LIMIT;
            ProviderOutcome outcome = transport.exchange(call(
                    account,
                    byId ? "get" : "find",
                    "GET",
                    path,
                    reference.clientReference(),
                    secret -> Request.of(headers(secret, login.token())),
                    Set.of(404)));
            if (outcome.status() == ProviderOutcome.Status.REJECTED
                    && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode())
                    && attempt == 0) {
                sessions.remove(account.installationId());
                continue;
            }
            if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
                return new EInvoiceStateOutcome.Unavailable(
                        outcome.errorCode() == null ? "UNKNOWN" : outcome.errorCode(),
                        outcome.detail() == null ? "" : outcome.detail());
            }
            return byId ? readOne(outcome.normalized(), reference) : readList(outcome.normalized(), reference);
        }
        return new EInvoiceStateOutcome.Unavailable("OPERATOR_AUTHENTICATION", "Didox refused the session twice");
    }

    private static EInvoiceStateOutcome readOne(Map<String, Object> body, EInvoiceDocumentReference reference) {
        if (Integer.valueOf(404).equals(body.get(ProviderHttpClient.STATUS_KEY))) {
            return new EInvoiceStateOutcome.NotFound();
        }
        Map<String, Object> document = unwrap(body);
        Integer status = firstInteger(document);
        if (status == null) {
            return new EInvoiceStateOutcome.Unavailable(
                    "STATE_UNREADABLE", "Didox answered the document without a status this adapter reads");
        }
        String id = documentId(document);
        return new EInvoiceStateOutcome.Known(
                id == null ? Objects.requireNonNull(reference.operatorDocumentId()) : id,
                DidoxStatus.of(status),
                DidoxStatus.raw(status));
    }

    /**
     * The answer to a lookup by number, which is how a send whose answer was lost is resolved.
     *
     * <p>Only a list that is <em>positively there and empty</em> says the operator holds nothing:
     * the platform turns "not found" on a lost send into "this never arrived" and frees the
     * statement for a second send, so an answer this adapter cannot read must never be read as
     * "not found". The shapes of Didox's answers are unverified (HorecaOS has no account); a missing
     * list, a row whose number or identifier this adapter does not read, or a list of other
     * numbers (the filter may have been ignored) all leave the document unresolved instead.
     *
     * <p>A number is shared by every attempt for a statement, so the rows under it may include the
     * documents of earlier attempts. Those, named in the reference, are set aside: the answer is
     * about this attempt, and adopting an earlier attempt's document would record its state on
     * this one and release a statement the earlier attempt's state has no say over.
     */
    private static EInvoiceStateOutcome readList(Map<String, Object> body, EInvoiceDocumentReference reference) {
        if (!(body.get("data") instanceof List<?> rows)) {
            return unreadableList("Didox answered the document list without a list this adapter reads");
        }
        if (rows.isEmpty()) {
            return new EInvoiceStateOutcome.NotFound();
        }
        List<Map<String, Object>> sameNumber = new ArrayList<>();
        for (Object row : rows) {
            Map<String, Object> candidate = EInvoicingOutcomes.map(row);
            String number = candidate == null ? null : EInvoicingOutcomes.text(candidate, NUMBER_KEYS);
            if (number == null) {
                return unreadableList("Didox listed a document with no number this adapter reads");
            }
            if (reference.documentNumber().equals(number)) {
                sameNumber.add(candidate);
            }
        }
        if (sameNumber.isEmpty()) {
            // Other numbers only: the search was by ours, but a filter Didox ignored would answer
            // exactly this, with ours on a later page.
            return unreadableList("Didox listed documents, none under this number");
        }
        List<Map<String, Object>> ours = new ArrayList<>();
        for (Map<String, Object> candidate : sameNumber) {
            String id = documentId(candidate);
            if (id == null) {
                return unreadableList("Didox listed a document under this number without an identifier");
            }
            if (!reference.otherAttemptDocumentIds().contains(id)) {
                ours.add(candidate);
            }
        }
        if (ours.isEmpty()) {
            // Everything under the number belongs to earlier attempts: nothing was created for this one,
            // unless the page was full and the rest is on the next.
            return rows.size() >= LIST_LIMIT
                    ? unreadableList("Didox's page of documents under this number was full")
                    : new EInvoiceStateOutcome.NotFound();
        }
        if (ours.size() > 1) {
            return new EInvoiceStateOutcome.Unavailable(
                    "STATE_AMBIGUOUS",
                    "Didox lists more than one document under this number that no earlier attempt accounts for");
        }
        Map<String, Object> match = ours.getFirst();
        String id = Objects.requireNonNull(documentId(match));
        Integer status = firstInteger(match);
        if (status == null) {
            return new EInvoiceStateOutcome.Unavailable(
                    "STATE_UNREADABLE", "Didox listed the document without a status this adapter reads");
        }
        return new EInvoiceStateOutcome.Known(id, DidoxStatus.of(status), DidoxStatus.raw(status));
    }

    private static EInvoiceStateOutcome unreadableList(String detail) {
        return new EInvoiceStateOutcome.Unavailable("STATE_UNREADABLE", detail);
    }

    // ----------------------------------------------------------------- login

    private record Session(String token, Instant expiresAt) {}

    private record Login(@Nullable String token, @Nullable EInvoiceSendOutcome failure) {}

    /**
     * A live session, signing in when there is none. A login writes nothing at the operator, so
     * a failure here is always "not sent".
     */
    private Login session(EInvoiceOperatorAccount account, String correlationId) {
        Session cached = sessions.get(account.installationId());
        if (cached != null && cached.expiresAt().isAfter(clock.instant())) {
            return new Login(cached.token(), null);
        }
        String taxId = account.config().get("sellerTaxpayerNumber");
        String locale = account.config().getOrDefault("locale", "ru");
        if (taxId == null || taxId.isBlank()) {
            return new Login(
                    null,
                    new EInvoiceSendOutcome.NotSent(
                            "OPERATOR_NOT_CONFIGURED", "The seller's taxpayer number is not set"));
        }
        ProviderOutcome outcome = transport.exchange(call(
                account,
                "login",
                "POST",
                "/v1/auth/" + encode(taxId) + "/password/" + encode(locale),
                correlationId,
                secret -> {
                    EInvoicingCredentials login = EInvoicingCredentials.parse(mapper, secret);
                    return Request.json(
                            Map.of("Partner-Authorization", login.required("partnerToken")),
                            Map.of("password", login.required("password")));
                },
                Set.of()));
        if (outcome.status() != ProviderOutcome.Status.SUCCESS) {
            String code = outcome.status() == ProviderOutcome.Status.REJECTED
                            && "PROVIDER_REJECTED".equals(outcome.errorCode())
                    ? "OPERATOR_LOGIN_REFUSED"
                    : outcome.errorCode() == null ? "OPERATOR_UNAVAILABLE" : outcome.errorCode();
            return new Login(
                    null, new EInvoiceSendOutcome.NotSent(code, outcome.detail() == null ? "" : outcome.detail()));
        }
        Map<String, Object> body = outcome.normalized();
        String token = EInvoicingOutcomes.text(body, List.of("token"));
        if (token == null) {
            Map<String, Object> data = EInvoicingOutcomes.map(body.get("data"));
            token = EInvoicingOutcomes.text(data, List.of("token"));
        }
        if (token == null) {
            return new Login(
                    null,
                    new EInvoiceSendOutcome.NotSent(
                            "OPERATOR_LOGIN_UNREADABLE", "Didox's login answer carries no session token"));
        }
        sessions.put(
                account.installationId(), new Session(token, clock.instant().plus(SESSION)));
        return new Login(token, null);
    }

    private Map<String, String> headers(String secret, @Nullable String token) {
        EInvoicingCredentials login = EInvoicingCredentials.parse(mapper, secret);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Partner-Authorization", login.required("partnerToken"));
        if (token != null) {
            headers.put("user-key", token);
        }
        return headers;
    }

    private static EInvoicingApiCall call(
            EInvoiceOperatorAccount account,
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
                account.baseUrl(),
                path,
                account.secretReference(),
                request,
                accepted,
                correlationId,
                null);
    }

    // --------------------------------------------------------------- mapping

    /**
     * The invoice as the partner SDK's {@code InvoiceBuilder} lays it out for {@code POST
     * /v1/documents/002/create}: money as decimals in sums, the VAT rate as a percentage, the
     * quantity as {@code Count}, and each line's gross in {@code Summa}.
     */
    static Map<String, Object> payload(EInvoiceDocument document) {
        String currency = document.currency();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Version", 1);
        payload.put("FacturaType", 0);
        payload.put(
                "FacturaDoc",
                Map.of(
                        "FacturaNo",
                        document.documentNumber(),
                        "FacturaDate",
                        document.documentDate().toString()));
        payload.put(
                "ContractDoc",
                Map.of(
                        "ContractNo",
                        document.contractNumber(),
                        "ContractDate",
                        document.contractDate().toString()));
        payload.put("SellerTin", document.seller().taxpayerNumber());
        payload.put("Seller", party(document.seller()));
        payload.put("BuyerTin", document.buyer().taxpayerNumber());
        payload.put("Buyer", party(document.buyer()));

        List<Map<String, Object>> products = new ArrayList<>();
        for (EInvoiceDocument.Line line : document.lines()) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("OrdNo", line.number());
            product.put("Name", line.name());
            product.put("CatalogCode", line.classificationCode());
            product.put("CatalogName", line.classificationName());
            product.put("PackageCode", line.packageCode());
            product.put("PackageName", line.packageName());
            product.put("Count", line.quantity());
            product.put("Summa", decimal(line.grossMinor(), currency));
            product.put("DeliverySum", decimal(line.netMinor(), currency));
            product.put("VatRate", new BigDecimal(line.vatPercentText()));
            product.put("VatSum", decimal(line.vatMinor(), currency));
            product.put("DeliverySumWithVat", decimal(line.grossMinor(), currency));
            product.put("WithoutVat", !document.vatApplies());
            products.add(product);
        }
        Map<String, Object> productList = new LinkedHashMap<>();
        productList.put("Tin", document.seller().taxpayerNumber());
        productList.put("HasVat", document.vatApplies());
        productList.put("HasExcise", false);
        productList.put("HasLgota", false);
        productList.put("HasCommittent", false);
        productList.put("Products", products);
        payload.put("ProductList", productList);
        return payload;
    }

    private static Map<String, Object> party(EInvoiceDocument.Party party) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Name", party.name());
        putIfPresent(out, "VatRegCode", party.vatRegistrationCode());
        putIfPresent(out, "Account", party.bankAccount());
        putIfPresent(out, "BankId", party.bankCode());
        putIfPresent(out, "Address", party.address());
        return out;
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static BigDecimal decimal(long minor, String currency) {
        return EInvoiceDocument.decimal(minor, currency);
    }

    private static @Nullable String documentId(Map<String, Object> body) {
        String id = EInvoicingOutcomes.text(body, ID_KEYS);
        if (id != null) {
            return id;
        }
        for (String wrapper : List.of("data", "document")) {
            id = EInvoicingOutcomes.text(EInvoicingOutcomes.map(body.get(wrapper)), ID_KEYS);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static @Nullable Integer firstInteger(Map<String, Object> body) {
        Integer status = EInvoicingOutcomes.integer(body, STATUS_KEYS);
        if (status != null) {
            return status;
        }
        for (String wrapper : List.of("data", "document")) {
            status = EInvoicingOutcomes.integer(EInvoicingOutcomes.map(body.get(wrapper)), STATUS_KEYS);
            if (status != null) {
                return status;
            }
        }
        return null;
    }

    private static Map<String, Object> unwrap(Map<String, Object> body) {
        Map<String, Object> data = EInvoicingOutcomes.map(body.get("data"));
        return data != null && firstInteger(body) == null ? data : body;
    }

    private static String encode(@Nullable String value) {
        return URLEncoder.encode(Objects.requireNonNull(value), StandardCharsets.UTF_8);
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
