package uz.horecaos.platform.payments.payme;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.TiyinAmount;
import uz.horecaos.platform.payments.infrastructure.payme.PaymeCheckoutLink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbound half of Payme, pinned.
 *
 * <p>Payme's documentation settles the base64 encoding with exactly one worked
 * example, and that example is 48 bytes long — a multiple of three — so it exhibits
 * neither {@code =} padding nor a {@code +} or {@code /} in its output. Everything
 * about those two cases is open question U19, and the only arbiter is a sandbox
 * transaction. These tests therefore pin the decisions rather than prove them, so
 * that a sandbox result can overturn one without anybody having to reconstruct why
 * it was made.
 */
class PaymeCheckoutLinkTests {

    /**
     * The documented worked example, reproduced byte for byte.
     *
     * <p>{@code m=587f72c72cac0d162c722ae2;ac.order_id=197;a=500}, which the docs
     * render as the link below. It is the whole of the evidence about this encoding:
     * standard RFC 4648 base64 of the raw ASCII payload, not URL-safe base64 and not
     * percent-encoded afterwards.
     */
    @Test
    @DisplayName("reproduces Payme's own documented checkout link exactly")
    void reproducesTheDocumentedExample() {
        String payload = PaymeCheckoutLink.payload(
                "587f72c72cac0d162c722ae2", "197", new TiyinAmount(500, "UZS"));

        assertThat(payload).isEqualTo("m=587f72c72cac0d162c722ae2;ac.order_id=197;a=500");
        assertThat(PaymeCheckoutLink.encode(payload))
                .isEqualTo("bT01ODdmNzJjNzJjYWMwZDE2MmM3MjJhZTI7YWMub3JkZXJfaWQ9MTk3O2E9NTAw");
        assertThat(PaymeCheckoutLink.url("https://checkout.paycom.uz", payload, true))
                .isEqualTo("https://checkout.paycom.uz/"
                        + "bT01ODdmNzJjNzJjYWMwZDE2MmM3MjJhZTI7YWMub3JkZXJfaWQ9MTk3O2E9NTAw");
    }

    /**
     * The amount is tiyin, and the multiplication happens once.
     *
     * <p>Five som is {@code a=500}, which is the docs' own example read the other
     * way round. The type is the guard: {@link PaymeCheckoutLink#payload} does not
     * accept a {@link SomAmount}, so an adapter that forgets to scale fails to
     * compile rather than charging a customer a hundredth of the price.
     */
    @Test
    @DisplayName("five som is five hundred tiyin in the link")
    void convertsSomToTiyinExactlyOnce() {
        TiyinAmount tiyin = TiyinAmount.of(new SomAmount(5, "UZS"));

        assertThat(tiyin.value()).isEqualTo(500);
        assertThat(PaymeCheckoutLink.payload("587f72c72cac0d162c722ae2", "197", tiyin))
                .endsWith(";a=500");
    }

    /**
     * <strong>Decision: padding is kept.</strong>
     *
     * <p>A real HorecaOS payload is 74 bytes plus the digits of the amount, so its
     * length is a multiple of three for one amount-digit-count in three and padding
     * appears for the other two. It cannot be treated as a rare case. The
     * alternative the provider notes suggest — lengthening the plaintext until it is
     * a multiple of three, for instance by appending {@code ;l=ru} — is deliberately
     * not done, because it would make the emitted link depend on how many digits the
     * amount happens to have, which is far worse to debug than a trailing equals
     * sign.
     */
    @Test
    @DisplayName("standard base64: the padding is kept")
    void keepsBase64Padding() {
        String payload = PaymeCheckoutLink.payload("a4c123b1612dd272d1371c17",
                "149d439536b3216fdaeeb975729fae92", new TiyinAmount(123801, "UZS"));

        assertThat(payload.length() % 3).isNotZero();
        assertThat(PaymeCheckoutLink.encode(payload)).isEqualTo(
                "bT1hNGMxMjNiMTYxMmRkMjcyZDEzNzFjMTc7YWMub3JkZXJfaWQ9MTQ5ZDQzOTUzNmIz"
                        + "MjE2ZmRhZWViOTc1NzI5ZmFlOTI7YT0xMjM4MDE=");
        assertThat(new String(Base64.getDecoder().decode(PaymeCheckoutLink.encode(payload)),
                StandardCharsets.US_ASCII)).isEqualTo(payload);
    }

    /**
     * <strong>Decision: only {@code /} is percent-encoded.</strong>
     *
     * <p>Of the three characters standard base64 adds beyond the alphanumerics,
     * {@code +} and {@code =} are legal inside a URL path segment and survive
     * unchanged, while {@code /} is the one that changes the URL's structure rather
     * than its content. The flag exists so a sandbox can settle it with a property
     * change; this pins both sides of it.
     *
     * <p>The payload here is synthetic, for the reason the next test records.
     */
    @Test
    @DisplayName("a path separator in the base64 is written %2F, and the flag turns that off")
    void percentEncodesOnlyThePathSeparator() {
        assertThat(PaymeCheckoutLink.encode("m=?")).isEqualTo("bT0/");

        assertThat(PaymeCheckoutLink.url("https://checkout.paycom.uz", "m=?", true))
                .isEqualTo("https://checkout.paycom.uz/bT0%2F");
        assertThat(PaymeCheckoutLink.url("https://checkout.paycom.uz", "m=?", false))
                .isEqualTo("https://checkout.paycom.uz/bT0/");
    }

    /**
     * The half of U19 that turns out not to apply to HorecaOS at all.
     *
     * <p>A HorecaOS payload is drawn from a closed alphabet: the literals {@code m=},
     * {@code ;ac.order_id=} and {@code ;a=}, a hexadecimal cashbox id, a
     * thirty-two-character hexadecimal order reference, and decimal digits. Over
     * that alphabet, at the fixed byte alignment the payload's fixed-width prefix
     * imposes, no three-byte group can encode to a {@code +} or a {@code /} — an
     * exhaustive enumeration of every three-byte window across every amount length
     * finds none. So the percent-encoding decision above is a guard against a case
     * the frozen account schema cannot produce, and it stays only because the schema
     * is the thing that guarantees it.
     *
     * <p>The sweep below is the bounded version of that enumeration, at the sizes a
     * unit test should take. Padding, by contrast, appears constantly — which is the
     * asymmetry worth remembering.
     */
    @Test
    @DisplayName("HorecaOS's own payloads never produce + or /, but do produce padding")
    void horecaosPayloadsNeverReachThePathSeparator() {
        boolean sawPadding = false;

        for (int amount = 1; amount <= 4000; amount++) {
            String encoded = PaymeCheckoutLink.encode(PaymeCheckoutLink.payload(
                    hexadecimal(amount, 24), hexadecimal(amount * 31L + 7, 32),
                    new TiyinAmount(amount, "UZS")));

            assertThat(encoded).doesNotContain("+").doesNotContain("/");
            sawPadding |= encoded.endsWith("=");
        }

        assertThat(sawPadding)
                .as("padding is the common case and cannot be treated as an edge")
                .isTrue();
    }

    /**
     * The POST form, which is what carries a {@code detail} object.
     *
     * <p>{@code account[order_id]} uses the bracket notation the docs specify, and
     * {@code callback} is omitted rather than sent empty when there is none — in
     * which case Payme falls back to the request's {@code Referer}. Either way the
     * return is a browser redirect that proves nothing: its {@code :transaction}
     * placeholder can be the literal string {@code "null"} on a perfectly good
     * payment, and only {@code PerformTransaction} is authoritative.
     */
    @Test
    @DisplayName("the POST form carries the account in bracket notation and omits what is absent")
    void buildsThePostForm() {
        Map<String, String> fields = PaymeCheckoutLink.formFields("587f72c72cac0d162c722ae2",
                "149d439536b3216fdaeeb975729fae92", new TiyinAmount(150000, "UZS"),
                "ru", null, "eyJ4IjoxfQ==");

        assertThat(fields)
                .containsEntry("merchant", "587f72c72cac0d162c722ae2")
                .containsEntry("amount", "150000")
                .containsEntry("account[order_id]", "149d439536b3216fdaeeb975729fae92")
                .containsEntry("lang", "ru")
                .containsEntry("detail", "eyJ4IjoxfQ==")
                .doesNotContainKey("callback");
    }

    /**
     * Payme's {@code Amount} is "a positive integer, greater than zero".
     *
     * <p>Refused here rather than at the checkout page, which shows the customer an
     * error they cannot act on.
     */
    @Test
    @DisplayName("a zero-amount link is refused before it is built")
    void refusesAZeroAmount() {
        assertThatThrownBy(() -> PaymeCheckoutLink.payload(
                "587f72c72cac0d162c722ae2", "197", new TiyinAmount(0, "UZS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    /** A deterministic stand-in for the hexadecimal identifiers the schema uses. */
    private static String hexadecimal(long seed, int length) {
        StringBuilder value = new StringBuilder(length);
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        while (value.length() < length) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            value.append(Long.toHexString(state >>> 32));
        }
        return value.substring(0, length);
    }
}
