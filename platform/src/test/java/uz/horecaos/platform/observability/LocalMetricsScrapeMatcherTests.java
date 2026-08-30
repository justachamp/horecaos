package uz.horecaos.platform.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the scrape rule that an integration test cannot reach: a request
 * from somewhere other than the container's own loopback.
 *
 * <p>A test that connects over a real socket always connects from 127.0.0.1, so
 * the refusal path has to be exercised against the matcher directly. It is worth
 * exercising: the failure mode of getting it wrong is that anything able to
 * reach the application port can read a full description of the platform's
 * internals without a credential.
 */
class LocalMetricsScrapeMatcherTests {

    private final LocalMetricsScrapeMatcher matcher = new LocalMetricsScrapeMatcher();

    @Test
    @DisplayName("a call from inside the container is permitted")
    void loopbackIsPermitted() {
        assertThat(matcher.matches(request("/actuator/prometheus", "127.0.0.1"))).isTrue();
        assertThat(matcher.matches(request("/actuator/prometheus", "::1"))).isTrue();
    }

    @Test
    @DisplayName("a call from anywhere else is refused, whatever it claims")
    void anythingElseIsRefused() {
        MockHttpServletRequest throughTheEdge = request("/actuator/prometheus", "172.18.0.4");
        throughTheEdge.addHeader("X-Forwarded-For", "127.0.0.1");

        assertThat(matcher.matches(throughTheEdge))
                .as("the address the container recorded is the input, not the one the caller sent")
                .isFalse();
    }

    @Test
    @DisplayName("an unrecorded address is refused rather than assumed local")
    void missingAddressIsRefused() {
        MockHttpServletRequest noListener = new MockHttpServletRequest("GET", "/actuator/prometheus");
        noListener.setRequestURI("/actuator/prometheus");

        assertThat(matcher.matches(noListener))
                .as("a rule that opens when its input is missing is not a rule")
                .isFalse();
    }

    @Test
    @DisplayName("it grants nothing beyond the scrape")
    void onlyTheScrapePath() {
        assertThat(matcher.matches(request("/actuator/env", "127.0.0.1"))).isFalse();
        assertThat(matcher.matches(request("/api/v1/control-plane/tenants", "127.0.0.1"))).isFalse();
    }

    private static MockHttpServletRequest request(String path, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        request.setAttribute(RawRemoteAddress.ATTRIBUTE, remoteAddress);
        return request;
    }
}
