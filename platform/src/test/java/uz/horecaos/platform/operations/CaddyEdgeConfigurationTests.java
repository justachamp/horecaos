package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every public origin redacts query strings and sets a referrer policy
 * (ADR 0029).
 *
 * <p>The three site blocks drifted apart: the API origin redacted and set
 * {@code Referrer-Policy}, the auth origin did neither, and the auth origin is
 * the one whose URLs carry credentials — an OIDC {@code code} and {@code state},
 * and Keycloak's one-time {@code session_code}. The media origin then arrived
 * copying the auth origin, and a presigned URL is an {@code X-Amz-Signature} in
 * a query string.
 *
 * <p>Checked as a property of the file rather than of one block, because the way
 * this went wrong was a fourth site block being added later from the wrong
 * template. Adding an origin without these two lines has to fail here.
 */
class CaddyEdgeConfigurationTests {

    private static final Path CADDYFILE = Path.of("infra/production/caddy/Caddyfile");

    /** A public site block opens with the origin placeholder the deploy substitutes. */
    private static final Pattern SITE_BLOCK =
            Pattern.compile("^\\{\\$(HORECAOS_\\w+_ORIGIN)\\} \\{$(.*?)^\\}$",
                    Pattern.DOTALL | Pattern.MULTILINE);

    private record Site(String origin, String body) {}

    private static List<Site> sites() throws IOException {
        String source = Files.readString(CADDYFILE, StandardCharsets.UTF_8);
        Matcher matcher = SITE_BLOCK.matcher(source);
        List<Site> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(new Site(matcher.group(1), matcher.group(2)));
        }
        return found;
    }

    @Test
    @DisplayName("every public origin is found, so the assertions below cover all of them")
    void allThreeOriginsAreParsed() throws IOException {
        assertThat(sites()).extracting(Site::origin)
                .containsExactlyInAnyOrder(
                        "HORECAOS_API_ORIGIN", "HORECAOS_AUTH_ORIGIN", "HORECAOS_MEDIA_ORIGIN");
    }

    @Test
    @DisplayName("no public origin logs a query string")
    void everyOriginRedactsTheQueryString() throws IOException {
        for (Site site : sites()) {
            assertThat(site.body())
                    .as("%s writes access logs with the query string intact", site.origin())
                    .contains("request>uri regexp");
        }
    }

    @Test
    @DisplayName("the error logger redacts too, or a 502 bypasses every site filter")
    void theDefaultLoggerRedactsTheQueryString() throws IOException {
        // The per-site `log` directive only configures the access logger. An
        // upstream that refuses a connection is written by `http.log.error.*`,
        // which no site logger includes, so it lands on the default logger with
        // the request verbatim. Verified against a running Caddy: before the
        // global block existed, a 502 on the auth origin logged the full
        // session_code.
        String source = Files.readString(CADDYFILE, StandardCharsets.UTF_8);
        // Not indexOf("{$HORECAOS_"): the global block's own `email {$HORECAOS_ACME_EMAIL}`
        // matches that and would cut the region being checked in half.
        String globalOptions = source.substring(0, source.indexOf("{$HORECAOS_API_ORIGIN}"));

        assertThat(globalOptions).contains("request>uri regexp");
    }

    @Test
    @DisplayName("the redaction keeps the path")
    void theRedactionStripsOnlyTheQuery() throws IOException {
        // Caddy does not unescape a backslash inside a quoted Caddyfile token, so
        // "\\?" reaches Go's regexp as an *optional literal backslash* — which
        // matches at position zero and replaces the entire URI, path included.
        // The access log then cannot distinguish a 404 on /admin/* from any other
        // 404, which is a log the disk and incident runbooks read.
        String source = Files.readString(CADDYFILE, StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("regexp \"\\\\?");
        assertThat(source).contains("regexp \"\\?.*$\" \"?redacted\"");
    }

    @Test
    @DisplayName("no public origin drops Referrer-Policy")
    void everyOriginSetsAReferrerPolicy() throws IOException {
        for (Site site : sites()) {
            assertThat(site.body())
                    .as("%s leaks its own URLs to whatever it links out to", site.origin())
                    .contains("Referrer-Policy");
        }
    }

    @Test
    @DisplayName("the auth origin sends no referrer at all")
    void theAuthOriginSendsNoReferrer() throws IOException {
        Site auth = sites().stream()
                .filter(site -> site.origin().equals("HORECAOS_AUTH_ORIGIN"))
                .findFirst()
                .orElseThrow();

        // Stricter than the API origin, and deliberately: a Keycloak login page
        // links out to identity providers, registration and terms, and its own URL
        // holds a one-time session_code. strict-origin-when-cross-origin would
        // already drop the query, but there is nothing on this origin worth
        // sending a referrer for.
        assertThat(auth.body()).contains("Referrer-Policy \"no-referrer\"");
    }
}
