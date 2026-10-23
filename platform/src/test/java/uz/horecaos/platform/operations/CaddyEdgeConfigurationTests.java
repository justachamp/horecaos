package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** The Caddyfile the production compose file mounts. It has more origins than the one above. */
    private static final Path DEPLOYED_CADDYFILE = Path.of("../deploy/infra/caddy/Caddyfile");

    /** An access-log filter on the request URI: the pattern, then what replaces each match. */
    private static final Pattern URI_FILTER = Pattern.compile("request>uri regexp \"([^\"]*)\" \"([^\"]*)\"");

    /** A public site block opens with the origin placeholder the deploy substitutes. */
    private static final Pattern SITE_BLOCK =
            Pattern.compile("^\\{\\$(HORECAOS_\\w+_ORIGIN)\\} \\{$(.*?)^\\}$", Pattern.DOTALL | Pattern.MULTILINE);

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
        assertThat(sites())
                .extracting(Site::origin)
                .containsExactlyInAnyOrder("HORECAOS_API_ORIGIN", "HORECAOS_AUTH_ORIGIN", "HORECAOS_MEDIA_ORIGIN");
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
        for (Path file : List.of(CADDYFILE, DEPLOYED_CADDYFILE)) {
            String source = Files.readString(file, StandardCharsets.UTF_8);

            assertThat(source).doesNotContain("regexp \"\\\\?");
            assertThat(source).contains("(\\?).*$");
            assertThat(redact(source, "/api/v1/orders/abc?q=secret"))
                    .as("%s: the path survives, the query does not", file)
                    .isEqualTo("/api/v1/orders/abc?redacted");
            assertThat(redact(source, "/admin/x")).as("%s", file).isEqualTo("/admin/x");
        }
    }

    @Test
    @DisplayName("a capability token in a path never reaches a log, on the origin that serves it or any other")
    void theTokensInAPathAreRedactedToo() throws IOException {
        // /providers/mail/unsubscribe/{token} withdraws a customer's email consent and
        // /providers/push/clicks/{token} reports a read. The token is the whole credential and it
        // is a path segment, so a filter that strips only the query writes it to stdout verbatim
        // for anyone with the edge logs to replay (ADR 0182).
        for (Path file : List.of(CADDYFILE, DEPLOYED_CADDYFILE)) {
            String source = Files.readString(file, StandardCharsets.UTF_8);

            assertThat(redact(source, "/providers/mail/unsubscribe/SECRETTOKEN123"))
                    .as("%s: an unsubscribe link", file)
                    .isEqualTo("/providers/mail/unsubscribe/redacted");
            assertThat(redact(source, "/providers/mail/unsubscribe/SECRETTOKEN123?x=1"))
                    .as("%s: and with a query", file)
                    .isEqualTo("/providers/mail/unsubscribe/redacted?redacted");
            assertThat(redact(source, "/providers/push/clicks/SECRETCLICK456"))
                    .as("%s: a push click", file)
                    .isEqualTo("/providers/push/clicks/redacted");
            assertThat(redact(source, "/providers/push/clicks/SECRETCLICK456/?a=b"))
                    .as("%s: and with a trailing slash and a query", file)
                    .isEqualTo("/providers/push/clicks/redacted/?redacted");
            assertThat(redact(source, "/providers/sms/1/receipts"))
                    .as("%s: other provider paths keep their path, which carries no credential", file)
                    .isEqualTo("/providers/sms/1/receipts");
        }
    }

    @Test
    @DisplayName("every log block redacts the same way, in both Caddyfiles")
    void everyLogBlockUsesTheSameFilter() throws IOException {
        // One literal everywhere, so a block copied from a stale template cannot quietly redact less.
        List<String> filters = new ArrayList<>();
        for (Path file : List.of(CADDYFILE, DEPLOYED_CADDYFILE)) {
            Matcher matcher = URI_FILTER.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                filters.add(matcher.group());
            }
        }

        assertThat(filters).as("the filters found").isNotEmpty();
        assertThat(new LinkedHashSet<>(filters)).as("distinct filters").hasSize(1);
    }

    /**
     * What the first access-log filter in this Caddyfile would write for this URI, applied with
     * Java's regexp. Go's and Java's agree on this pattern (an anchor, a non-capturing group, an
     * alternation); only the replacement syntax differs, {@code ${1}} for Go and {@code $1} here.
     */
    private static String redact(String caddyfile, String uri) {
        Matcher filter = URI_FILTER.matcher(caddyfile);
        assertThat(filter.find()).as("an access-log filter on request>uri").isTrue();
        String replacement = filter.group(2).replaceAll("\\$\\{(\\d+)}", "\\$$1");
        return Pattern.compile(filter.group(1)).matcher(uri).replaceAll(replacement);
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
