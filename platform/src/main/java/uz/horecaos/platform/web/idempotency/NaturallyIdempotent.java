package uz.horecaos.platform.web.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that replay protection is supplied by a durable natural key rather
 * than by a client-provided {@code Idempotency-Key} (ADR 0031, ADR 0049).
 *
 * <p>This is intentionally not a trigger for {@link IdempotencyInterceptor}.
 * The storage transaction must return the first result when the same natural key
 * is received again. Use this only where the caller cannot supply HorecaOS's header
 * and a database uniqueness constraint is stronger than client discipline.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NaturallyIdempotent {}
