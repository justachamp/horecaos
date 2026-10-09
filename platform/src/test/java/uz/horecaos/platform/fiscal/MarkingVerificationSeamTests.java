package uz.horecaos.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uz.horecaos.platform.fiscal.api.MarkingVerificationPort;
import uz.horecaos.platform.fiscal.api.MarkingVerificationPort.Verdict;
import uz.horecaos.platform.fiscal.application.FiscalPortConfiguration;

/**
 * ADR 0161, Decision 9: the state system's check of a marking code is a seam and not a call. Nothing in this
 * repository can make it real (the retailer API is an open input), so what is asserted is the honest
 * default -- every code answers {@code NOT_WIRED} and nobody is asked -- and that an implementation, when
 * one exists, replaces the default without anything else changing.
 */
class MarkingVerificationSeamTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FiscalPortConfiguration.class);

    @Test
    @DisplayName("with nothing behind it every code is NOT_WIRED")
    void theDefaultIsNotWired() {
        runner.run(context -> {
            MarkingVerificationPort port = context.getBean(MarkingVerificationPort.class);

            assertThat(port.verify(UUID.randomUUID(), "010460000000001521UnwiredSerial01"))
                    .isEqualTo(Verdict.NOT_WIRED);
            assertThat(port.verify(UUID.randomUUID(), "010460000000001521UnwiredSerial02"))
                    .as("not a verdict about the code: a code is never read as VERIFIED because nobody checked")
                    .isNotEqualTo(Verdict.VERIFIED);
        });
    }

    @Test
    @DisplayName("an implementation replaces the default and is the one the application context uses")
    void anImplementationBacksTheDefaultOff() {
        AtomicInteger asked = new AtomicInteger();
        runner.withBean(MarkingVerificationPort.class, () -> (tenantId, code) -> {
                    asked.incrementAndGet();
                    return Verdict.REJECTED;
                })
                .run(context -> {
                    assertThat(context.getBeansOfType(MarkingVerificationPort.class))
                            .hasSize(1);

                    Verdict verdict = context.getBean(MarkingVerificationPort.class)
                            .verify(UUID.randomUUID(), "010460000000001521RealSerial0001");

                    assertThat(verdict).isEqualTo(Verdict.REJECTED);
                    assertThat(asked.get()).isEqualTo(1);
                });
    }
}
