package uz.horecaos.platform.customers.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a {@link CustomerExportParticipant} answers for, declared where a build can read it without constructing the bean
 * (ADR 0029). The export twin of {@link ErasesCustomerData}: {@code ExportInventoryTests} requires every table an erasure
 * participant answers for to be named here or excused, with a reason, in its exemption list, and {@code
 * CustomerExportService} refuses to start with a participant that declares nothing, because a manifest that cannot name
 * what it covered is not an answer to a subject-access request.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExportsCustomerData {

    /** The section's name in the bundle: short, stable, not personal ({@code ordering}, {@code loyalty}). */
    String name();

    /**
     * Every table the participant reads values from, as {@code schema.table}. A table is listed even when a given
     * export finds nothing in it: the manifest says "looked, found 0", which is different from not looking.
     */
    String[] tables();
}
