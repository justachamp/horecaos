package uz.qoida.platform.iam.api.protection;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the data class of a field or record component (ADR 0029).
 *
 * <p>This is the mechanism the interim name-based checks in ADR 0032 and ADR
 * 0027 were standing in for. A declared classification beats guessing from a
 * field name: it survives renaming, it covers fields whose names give nothing
 * away, and it is reviewable in a diff.
 *
 * <p>Applying it to a type classifies every component that does not declare its
 * own, so a whole address or contact record can be marked once.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.TYPE})
public @interface Classified {

    DataClass value();

    /**
     * Why this classification, where it is not obvious. Read in review, not by
     * code.
     */
    String reason() default "";
}
