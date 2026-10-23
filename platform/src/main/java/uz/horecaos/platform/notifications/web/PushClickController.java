package uz.horecaos.platform.notifications.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.notifications.application.PushClickService;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Where a storefront's service worker reports that a customer tapped a promotional notification
 * (ADR 0182, D14). The tap is the read.
 *
 * <p>{@code permitAll} on the filter chain and authorised by possession of the token, which sits in the
 * notification's encrypted payload and so can only be known by the browser that received it. The worker
 * posts with {@code mode: 'no-cors'} and no body, a request a browser sends without asking the platform's
 * permission first, so no CORS configuration is needed and none is opened. An unknown token and an
 * expired one answer identically, so the endpoint is not an oracle for which tokens ever existed. The
 * token is a path segment and is never logged.
 *
 * <p>Hidden from the published OpenAPI document, like the other callbacks that are not an API for
 * anyone to write a client against: its one caller is the service worker this platform ships.
 */
@RestController
@RequestMapping("/providers/push/clicks")
@Hidden
public class PushClickController {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,64}");
    private static final RateLimiter.Policy LIMIT = RateLimiter.Policy.perMinute(60);

    private final PushClickService clicks;
    private final RateLimiter rateLimiter;

    public PushClickController(PushClickService clicks, RateLimiter rateLimiter) {
        this.clicks = clicks;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{token}")
    public ResponseEntity<Void> click(@PathVariable String token, HttpServletRequest request) {
        if (!allowed(request)) {
            return answer(HttpStatus.TOO_MANY_REQUESTS);
        }
        boolean known = TOKEN.matcher(token).matches() && clicks.click(token);
        return answer(known ? HttpStatus.NO_CONTENT : HttpStatus.NOT_FOUND);
    }

    private static ResponseEntity<Void> answer(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private boolean allowed(HttpServletRequest request) {
        return rateLimiter
                .check(new RateLimiter.Key("push.click", null, callerKey(request)), LIMIT)
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
