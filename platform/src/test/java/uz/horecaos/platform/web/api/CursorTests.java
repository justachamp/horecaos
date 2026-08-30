package uz.horecaos.platform.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import uz.horecaos.platform.web.api.Cursor.CursorSigner;

/** ADR 0031: cursors are opaque, signed, and bound to their filter set. */
class CursorTests {

    private static final CursorSigner SIGNER =
            CursorSigner.hmacSha256("test-signing-key".getBytes(StandardCharsets.UTF_8));
    private static final CursorSigner OTHER_SIGNER =
            CursorSigner.hmacSha256("another-key".getBytes(StandardCharsets.UTF_8));
    private static final String FILTERS = "filter-hash-a";

    @Test
    void roundTripsThroughEncoding() {
        Cursor cursor = new Cursor("2026-08-20T10:00:00Z#018f6f4e", FILTERS);

        Cursor decoded = Cursor.decode(cursor.encode(SIGNER), FILTERS, SIGNER).orElseThrow();

        assertThat(decoded).isEqualTo(cursor);
    }

    @Test
    void isOpaqueToTheClient() {
        String encoded = new Cursor("018f6f4e-tenant-a", FILTERS).encode(SIGNER);

        assertThat(encoded)
                .as("a cursor must not read as a value a client can construct")
                .doesNotContain("018f6f4e-tenant-a");
    }

    @Test
    void rejectsATamperedCursor() {
        String encoded = new Cursor("row-1", FILTERS).encode(SIGNER);
        String tampered = encoded.substring(0, encoded.length() - 4) + "AAAA";

        assertThat(Cursor.decode(tampered, FILTERS, SIGNER)).isEmpty();
    }

    @Test
    void rejectsACursorSignedByAnotherKey() {
        String encoded = new Cursor("row-1", FILTERS).encode(OTHER_SIGNER);

        assertThat(Cursor.decode(encoded, FILTERS, SIGNER)).isEmpty();
    }

    @Test
    void rejectsACursorIssuedForADifferentFilterSet() {
        String encoded = new Cursor("row-1", FILTERS).encode(SIGNER);

        assertThat(Cursor.decode(encoded, "filter-hash-b", SIGNER))
                .as("changing filters mid-iteration must fail rather than return incoherent pages")
                .isEmpty();
    }

    @Test
    void rejectsMalformedInput() {
        assertThat(Cursor.decode("not-base64!!", FILTERS, SIGNER)).isEmpty();
        assertThat(Cursor.decode("", FILTERS, SIGNER)).isEmpty();
        assertThat(Cursor.decode(null, FILTERS, SIGNER)).isEmpty();
    }

    @Test
    void rejectsASortKeyContainingTheSeparator() {
        assertThatThrownBy(() -> new Cursor("has|separator", FILTERS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clampsPageLimits() {
        assertThat(Page.limitOrDefault(null)).isEqualTo(Page.DEFAULT_LIMIT);
        assertThat(Page.limitOrDefault(10)).isEqualTo(10);
        assertThat(Page.limitOrDefault(10_000)).isEqualTo(Page.MAXIMUM_LIMIT);
        assertThatThrownBy(() -> Page.limitOrDefault(0)).isInstanceOf(ApiException.class);
    }
}
