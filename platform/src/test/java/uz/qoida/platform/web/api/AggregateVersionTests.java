package uz.qoida.platform.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/** ADR 0031 optimistic concurrency over HTTP. */
class AggregateVersionTests {

    @Test
    void rendersAWeakValidator() {
        assertThat(AggregateVersion.toETag(7))
                .as("two responses at one version are equivalent without being byte-identical")
                .isEqualTo("W/\"7\"");
    }

    @Test
    void readsAWeakOrStrongIfMatch() {
        assertThat(AggregateVersion.fromIfMatch(withIfMatch("W/\"7\""))).contains(7L);
        assertThat(AggregateVersion.fromIfMatch(withIfMatch("\"7\""))).contains(7L);
        assertThat(AggregateVersion.fromIfMatch(withIfMatch("7"))).contains(7L);
    }

    @Test
    void anAbsentIfMatchIsEmptyRatherThanZero() {
        assertThat(AggregateVersion.fromIfMatch(new MockHttpServletRequest())).isEmpty();
    }

    @Test
    void aMalformedIfMatchIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> AggregateVersion.fromIfMatch(withIfMatch("not-a-version")))
                .as("a malformed precondition must never be treated as no precondition")
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void aRequiredIfMatchCannotBeSkippedByOmittingIt() {
        assertThatThrownBy(() -> AggregateVersion.requireIfMatch(new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("If-Match");
    }

    @Test
    void aStaleVersionReportsBothVersions() {
        assertThatThrownBy(() -> AggregateVersion.requireMatch(3, 5))
                .isInstanceOf(ApiException.class)
                .extracting(failure -> ((ApiException) failure).errorCode())
                .isEqualTo(ErrorCode.STALE_VERSION);
    }

    @Test
    void aMatchingVersionPasses() {
        AggregateVersion.requireMatch(5, 5);
    }

    private static MockHttpServletRequest withIfMatch(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.IF_MATCH, value);
        return request;
    }
}
