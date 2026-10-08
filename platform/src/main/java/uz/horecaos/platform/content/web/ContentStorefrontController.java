package uz.horecaos.platform.content.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.application.ContentHomeService;
import uz.horecaos.platform.content.application.ContentMetrics;
import uz.horecaos.platform.content.application.HomeDocument;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * What a brand puts in front of its customers, as the storefront reads it (ADR 0157, Decision 9).
 *
 * <p>Unauthenticated by design and listed one path at a time in {@code SecurityConfiguration}: it is
 * the same document for every visitor of a brand, a channel and a language, carries no personal
 * data, and is safe for the CDN edge ADR 0034 and ADR 0010 anticipate. A body-digest {@code ETag}
 * and {@code Cache-Control: public, max-age=60} make it cheap to revalidate; a withdrawal therefore
 * reaches customers within a minute, which is the stated trade-off and not a defect.
 *
 * <p>A failure of this read never blanks the menu: it is a request of its own, issued in parallel,
 * and a storefront that cannot read it renders without content.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}/content")
@Tag(name = "Storefront content", description = "The home content a brand has published")
public class ContentStorefrontController {

    /** How long a browser or an edge may reuse the document without asking. */
    static final Duration MAX_AGE = Duration.ofSeconds(60);

    /** Serialises with map entries in key order, so two equal documents always digest alike. */
    private static final JsonMapper DIGEST_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final ContentHomeService home;
    private final ContentMetrics metrics;

    public ContentStorefrontController(ContentHomeService home, ContentMetrics metrics) {
        this.home = home;
        this.metrics = metrics;
    }

    @GetMapping("/home")
    @Operation(
            summary = "The home content of a brand on a channel",
            description = "Banners and promo cards in the carousel, product groups, and from later releases "
                    + "stories, a promo grid and a pop-up, in order, with ready-made image rendition URLs. "
                    + "`channel` is the tenant's channel code, as the menu read takes it; `locale` is a BCP 47 "
                    + "tag, a bare `uz` read as `uz-Latn`. A text the visitor's language lacks is served in the "
                    + "brand's default and `textLocale` says which. The same for every visitor; an unknown "
                    + "brand, a brand of another tenant and an unknown channel are one 404.")
    public ResponseEntity<HomeDocument> home(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam String channel,
            @RequestParam(required = false) @Nullable String locale) {
        HomeDocument document;
        try {
            document = home.home(tenantId, brandId, channel, locale).orElse(null);
        } catch (RuntimeException failure) {
            metrics.read("error");
            throw failure;
        }
        if (document == null) {
            metrics.read("not_found");
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such brand or channel");
        }
        metrics.read("ok");
        return ResponseEntity.ok()
                // There is no timestamp in the body, so the digest moves only when content does.
                .eTag("\"" + digest(document) + "\"")
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(document);
    }

    private static String digest(HomeDocument document) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(DIGEST_MAPPER.writeValueAsString(document).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
