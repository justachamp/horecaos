package uz.horecaos.platform.support;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Runs a block as a signed-in staff member, for a test that drives an authoring service directly
 * instead of through its controller.
 *
 * <p>Since staff row 9.3a, the configuration-authoring services record who changed what (ADR
 * 0027) by asking {@code CurrentActor}, which refuses a caller with no token. A request always
 * has one; a fixture that reaches the service without a request has to say who it is acting as,
 * and this is how. The previous authentication is put back afterwards, so a test that leaves
 * nothing behind cannot make the next one pass or fail.
 */
public final class SignedInStaff {

    private SignedInStaff() {}

    public static void run(String subject, Runnable action) {
        call(subject, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(String subject, Supplier<T> action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(
                        Jwt.withTokenValue("signed-in-" + subject)
                                .header("alg", "none")
                                .subject(subject)
                                .build(),
                        List.of()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
