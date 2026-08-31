package uz.horecaos.platform.payments.click;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.infrastructure.click.ClickCallbackRequest;
import uz.horecaos.platform.payments.infrastructure.click.ClickCheckoutLink;
import uz.horecaos.platform.payments.infrastructure.click.ClickPrepareId;
import uz.horecaos.platform.payments.infrastructure.click.ClickSignature;

/**
 * Click's two signatures, and the defect that otherwise reaches production
 * (ADR 0013).
 *
 * <p>The digests asserted below are computed from the worked examples in
 * {@code docs/providers/click-merchant-api.md}. Click's own documentation contains
 * no worked example with concrete values, so these are the only fixed points
 * available and they are worth pinning: a change to the concatenation order, to the
 * field list, or to the encoding is otherwise invisible until a live payment fails
 * with {@code -1 SIGN CHECK FAILED!}.
 */
class ClickSignatureTests {

    private static final String SECRET = "SECRET123";
    private static final String CLICK_TRANS_ID = "3737503";
    private static final String SERVICE_ID = "12345";
    private static final String MERCHANT_TRANS_ID = "order_9001";
    private static final String AMOUNT = "1000.00";
    private static final String PREPARE_SIGN_TIME = "2026-08-22 14:03:11";
    private static final String COMPLETE_SIGN_TIME = "2026-08-22 14:03:19";
    private static final String MERCHANT_PREPARE_ID = "778";

    private static final String PREPARE_DIGEST = "9f73df6a589039c3afde3e2039720e46";
    private static final String COMPLETE_DIGEST = "a55a9138d88a9f9ee6fa86bece724e0b";

    @Test
    @DisplayName("Prepare signs seven fields with the secret in the middle")
    void prepareDigestMatchesTheWorkedExample() {
        assertThat(ClickSignature.prepare(
                        SECRET, CLICK_TRANS_ID, SERVICE_ID, MERCHANT_TRANS_ID, AMOUNT, "0", PREPARE_SIGN_TIME))
                .isEqualTo(PREPARE_DIGEST);
    }

    @Test
    @DisplayName("Complete signs eight, inserting merchant_prepare_id after merchant_trans_id")
    void completeDigestMatchesTheWorkedExample() {
        assertThat(ClickSignature.complete(
                        SECRET,
                        CLICK_TRANS_ID,
                        SERVICE_ID,
                        MERCHANT_TRANS_ID,
                        MERCHANT_PREPARE_ID,
                        AMOUNT,
                        "1",
                        COMPLETE_SIGN_TIME))
                .isEqualTo(COMPLETE_DIGEST);
    }

    @Test
    @DisplayName("a Complete verified with the Prepare formula fails")
    void thetwoFormulasAreNotInterchangeable() {
        // The two calls sign different field lists. Verifying a Complete with the
        // Prepare formula is the mistake a single shared helper invites, and it
        // presents as an intermittent -1 on exactly the call that credits an order.
        String prepareFormulaOverCompleteFields = ClickSignature.prepare(
                SECRET, CLICK_TRANS_ID, SERVICE_ID, MERCHANT_TRANS_ID, AMOUNT, "1", COMPLETE_SIGN_TIME);

        assertThat(ClickSignature.matches(prepareFormulaOverCompleteFields, COMPLETE_DIGEST))
                .isFalse();
    }

    @Test
    @DisplayName("a signature over a reformatted amount fails")
    void reformattingTheAmountBreaksTheSignature() {
        // The defect this test exists for. Click sends the amount as form text and
        // may legitimately send 1000, 1000.0 or 1000.00 for one figure. Parsing it
        // to a number and rendering it back — even to the same value — changes the
        // MD5, and it is the commonest cause of a spurious -1 SIGN CHECK FAILED!.
        String overTheRawString = ClickSignature.prepare(
                SECRET, CLICK_TRANS_ID, SERVICE_ID, MERCHANT_TRANS_ID, "1000.00", "0", PREPARE_SIGN_TIME);
        String overTheReformattedString = ClickSignature.prepare(
                SECRET, CLICK_TRANS_ID, SERVICE_ID, MERCHANT_TRANS_ID, "1000", "0", PREPARE_SIGN_TIME);

        assertThat(overTheRawString).isEqualTo(PREPARE_DIGEST);
        assertThat(overTheReformattedString).isNotEqualTo(overTheRawString);
        assertThat(ClickSignature.matches(overTheReformattedString, PREPARE_DIGEST))
                .isFalse();
    }

    @Test
    @DisplayName("the digest is taken from the request exactly as it arrived")
    void expectedReadsTheRawRequest() {
        ClickCallbackRequest prepare = ClickCallbackRequest.fromForm(form("0", AMOUNT, PREPARE_SIGN_TIME, null));
        ClickCallbackRequest complete =
                ClickCallbackRequest.fromForm(form("1", AMOUNT, COMPLETE_SIGN_TIME, MERCHANT_PREPARE_ID));

        assertThat(ClickSignature.expected(SECRET, prepare)).isEqualTo(PREPARE_DIGEST);
        assertThat(ClickSignature.expected(SECRET, complete)).isEqualTo(COMPLETE_DIGEST);
    }

    @Test
    @DisplayName("click_paydoc_id, error and error_note are outside both signatures")
    void unsignedFieldsDoNotChangeTheDigest() {
        Map<String, String> form = form("0", AMOUNT, PREPARE_SIGN_TIME, null);
        form.put("click_paydoc_id", "987654321");
        form.put("error", "0");
        form.put("error_note", "Success");

        assertThat(ClickSignature.expected(SECRET, ClickCallbackRequest.fromForm(form)))
                .isEqualTo(PREPARE_DIGEST);
    }

    @Test
    @DisplayName("the comparison tolerates casing and refuses a missing signature")
    void comparisonIsCaseInsensitiveAndNullSafe() {
        assertThat(ClickSignature.matches(PREPARE_DIGEST, PREPARE_DIGEST.toUpperCase(Locale.ROOT)))
                .isTrue();
        assertThat(ClickSignature.matches(PREPARE_DIGEST, "  " + PREPARE_DIGEST + " "))
                .isTrue();
        assertThat(ClickSignature.matches(PREPARE_DIGEST, null)).isFalse();
        assertThat(ClickSignature.matches(PREPARE_DIGEST, "")).isFalse();
    }

    @Test
    @DisplayName("the Auth header is sha1(timestamp + secret), not an HMAC")
    void authHeaderMatchesTheWorkedExample() {
        assertThat(ClickSignature.authHeader("3333", SECRET, 1712345678L))
                .isEqualTo("3333:4d3f62489dbc19114297581bcfa0d906f84df0cd:1712345678");
    }

    @Test
    @DisplayName("merchant_prepare_id is a function of the attempt, not a fresh value")
    void prepareIdIsDeterministicAndPositive() {
        UUID attempt = UUID.fromString("2f1c2f4e-6d0a-4a5b-9c3d-0f1e2d3c4b5a");

        int first = ClickPrepareId.forAttempt(attempt);
        int second = ClickPrepareId.forAttempt(attempt);

        assertThat(first).isEqualTo(second).isNotNegative();
        assertThat(ClickPrepareId.matches(attempt, Integer.toString(first))).isTrue();
        assertThat(ClickPrepareId.matches(attempt, Integer.toString(first + 1))).isFalse();
        assertThat(ClickPrepareId.forAttempt(UUID.randomUUID())).isNotEqualTo(first);
    }

    @Test
    @DisplayName("the payment link is unsigned, and its amount is formatted N.NN")
    void checkoutLinkCarriesNoSignature() {
        String link = ClickCheckoutLink.build(
                "777", SERVICE_ID, "3333", MERCHANT_TRANS_ID, new SomAmount(1_000_000, "UZS"), null, null);

        assertThat(link)
                .startsWith(ClickCheckoutLink.BASE)
                .contains("amount=1000000.00")
                .contains("transaction_param=" + MERCHANT_TRANS_ID)
                // Unsigned by construction. The amount here is attacker-controlled,
                // which is why the amount HorecaOS enforces is the one checked in
                // Prepare against the committed attempt.
                .doesNotContain("sign_string")
                .doesNotContain("SIGN_STRING");
    }

    @Test
    @DisplayName("an amount that is not whole som is refused rather than rounded")
    void fractionalAmountsAreNotAccepted() {
        assertThat(ClickCallbackRequest.fromForm(form("0", "1000.00", PREPARE_SIGN_TIME, null))
                        .amountAsSom())
                .contains(1000L);
        assertThat(ClickCallbackRequest.fromForm(form("0", "1000.50", PREPARE_SIGN_TIME, null))
                        .amountAsSom())
                .isEmpty();
        assertThat(ClickCallbackRequest.fromForm(form("0", "nonsense", PREPARE_SIGN_TIME, null))
                        .amountAsSom())
                .isEmpty();
    }

    @Test
    @DisplayName("a partially missing body is answered -8, not only an empty one")
    void requiredFieldsAreCheckedIndividually() {
        // The Django reference's isset helper is true only when every required
        // field is absent, so a request missing just sign_time sails past its -8.
        Map<String, String> missingSignTime = form("0", AMOUNT, PREPARE_SIGN_TIME, null);
        missingSignTime.remove("sign_time");

        assertThat(ClickCallbackRequest.fromForm(form("0", AMOUNT, PREPARE_SIGN_TIME, null))
                        .hasEveryRequiredField())
                .isTrue();
        assertThat(ClickCallbackRequest.fromForm(missingSignTime).hasEveryRequiredField())
                .isFalse();

        // Complete needs merchant_prepare_id and Prepare does not.
        assertThat(ClickCallbackRequest.fromForm(form("1", AMOUNT, COMPLETE_SIGN_TIME, null))
                        .hasEveryRequiredField())
                .isFalse();
        assertThat(ClickCallbackRequest.fromForm(form("1", AMOUNT, COMPLETE_SIGN_TIME, MERCHANT_PREPARE_ID))
                        .hasEveryRequiredField())
                .isTrue();
    }

    private static Map<String, String> form(
            String action, String amount, String signTime, @Nullable String merchantPrepareId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("click_trans_id", CLICK_TRANS_ID);
        form.put("service_id", SERVICE_ID);
        form.put("merchant_trans_id", MERCHANT_TRANS_ID);
        form.put("amount", amount);
        form.put("action", action);
        form.put("sign_time", signTime);
        form.put("sign_string", "irrelevant-to-these-assertions");
        if (merchantPrepareId != null) {
            form.put("merchant_prepare_id", merchantPrepareId);
        }
        return form;
    }
}
