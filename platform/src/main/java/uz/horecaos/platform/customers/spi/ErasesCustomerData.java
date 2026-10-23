package uz.horecaos.platform.customers.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a {@link CustomerErasureParticipant} answers for, declared where a build can read it without
 * constructing the bean (ADR 0029).
 *
 * <p>The erasure inventory guard ({@code ErasureInventoryTests}) lists every table that carries a customer id
 * or a PERSONAL-class column, and requires each to be either named here or named, with a reason, in its
 * exemption list. Declaring the tables on the class that erases them is what keeps the claim next to the code:
 * a participant that stops touching a table has to stop declaring it, and a module that starts storing a phone
 * number in a new table fails the build until somebody says who erases it.
 *
 * <p>The declaration is read at startup too: {@code CustomerErasureService} refuses to start with a participant
 * that carries none, because an erasure proof that cannot name what it covered is not a proof.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ErasesCustomerData {

    /**
     * The participant's name in the erasure proof: short, stable, and not personal ({@code marketing},
     * {@code ordering}). Two participants in one module use different names.
     */
    String name();

    /**
     * Every table the participant overwrites, deletes from or reads in order to find what to overwrite, as
     * {@code schema.table}. A table is listed even when a given erasure finds nothing in it: the proof says
     * "looked, found 0", which is different from not looking.
     */
    String[] tables();
}
