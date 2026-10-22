package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.notifications.application.EmailUnsubscribeService;
import uz.horecaos.platform.notifications.application.EmailUnsubscribeService.Preview;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The way out of a campaign email (ADR 0182, D13; RFC 8058).
 *
 * <p>{@code GET} shows a page and changes nothing, because mail scanners and link previewers fetch
 * every URL in a message. {@code POST} unsubscribes, whether it comes from the button on that page
 * or from a mail client's own «unsubscribe» button, which posts {@code List-Unsubscribe=One-Click}
 * to the address in the header with no session and no page.
 *
 * <p>{@code permitAll} on the filter chain and authorised by possession of the token, which is
 * 192 random bits stored only as a hash. An unknown token and an expired one answer identically,
 * so the endpoint is not an oracle for which tokens ever existed. The token is a path segment and
 * is never logged; the page sends {@code Referrer-Policy: no-referrer} and loads nothing external,
 * so it cannot leak the address it was opened at.
 *
 * <p>Hidden from the published OpenAPI document, like the other callbacks that are not
 * HorecaOS's to define: the contract is the RFC's. The page is plain server-rendered HTML in the
 * message's language, with no storefront chrome (ADR 0182 records the trade-off).
 */
@RestController
@RequestMapping("/providers/mail/unsubscribe")
@Hidden
public class EmailUnsubscribeController {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,64}");
    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.perMinute(30);

    private final EmailUnsubscribeService unsubscribes;
    private final RateLimiter rateLimiter;

    public EmailUnsubscribeController(EmailUnsubscribeService unsubscribes, RateLimiter rateLimiter) {
        this.unsubscribes = unsubscribes;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/{token}")
    public ResponseEntity<String> page(@PathVariable String token, HttpServletRequest request) {
        if (!allowed(request)) {
            return answer(HttpStatus.TOO_MANY_REQUESTS, UnsubscribePages.tooManyAttempts());
        }
        Optional<Preview> preview = TOKEN.matcher(token).matches() ? unsubscribes.preview(token) : Optional.empty();
        return preview.map(found -> answer(HttpStatus.OK, UnsubscribePages.confirm(found.brandName(), found.locale())))
                .orElseGet(() -> answer(HttpStatus.NOT_FOUND, UnsubscribePages.unknownLink()));
    }

    @PostMapping("/{token}")
    public ResponseEntity<String> unsubscribe(@PathVariable String token, HttpServletRequest request) {
        if (!allowed(request)) {
            return answer(HttpStatus.TOO_MANY_REQUESTS, UnsubscribePages.tooManyAttempts());
        }
        Optional<Preview> done = TOKEN.matcher(token).matches() ? unsubscribes.unsubscribe(token) : Optional.empty();
        return done.map(found -> answer(HttpStatus.OK, UnsubscribePages.done(found.brandName(), found.locale())))
                .orElseGet(() -> answer(HttpStatus.NOT_FOUND, UnsubscribePages.unknownLink()));
    }

    private static ResponseEntity<String> answer(HttpStatus status, String html) {
        return ResponseEntity.status(status)
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'")
                .body(html);
    }

    private boolean allowed(HttpServletRequest request) {
        return rateLimiter
                .check(new RateLimiter.Key("mail.unsubscribe", null, callerKey(request)), LIMIT)
                .allowed();
    }

    /** The caller's address, hashed: the limiter needs a key, not an address to keep (ADR 0029). */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        String key = address == null || address.isBlank() ? "unattributed" : address;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
