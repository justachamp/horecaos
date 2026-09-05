package uz.horecaos.platform.configuration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Gates a bean or {@code @Configuration} class on {@link PlatformRole#runsWorkerWork()}
 * — present when {@code horecaos.runtime.role} is {@code worker} or {@code both}, or
 * unset. Absent when the role is {@code app}.
 *
 * <p>This is the ADR 0023 {@code app}/{@code worker} split's whole mechanism: one
 * property, read once per annotated element, composed with whatever other {@code
 * @Conditional} the element already carries (Spring ANDs every condition present on one
 * element) rather than a registry of jobs somewhere else that would need to stay in sync
 * with the classes that actually schedule work.
 *
 * <p>Applied to {@link SchedulingConfiguration} — the single class that carries {@code
 * @EnableScheduling} for the whole platform — so that a role of {@code app} disables
 * every {@code @Scheduled} method on every module uniformly, including ones added after
 * this record, without editing each of them. Applied additionally to the two ADR 0006
 * inbox Kafka listeners, alongside their existing {@code
 * horecaos.messaging.inbox.listener.enabled} switch, because {@code @EnableScheduling}
 * has no reach over a {@code @KafkaListener}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnWorkerRoleCondition.class)
public @interface ConditionalOnWorkerRole {}
