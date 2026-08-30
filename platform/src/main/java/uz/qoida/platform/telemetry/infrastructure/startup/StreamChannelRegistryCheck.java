package uz.qoida.platform.telemetry.infrastructure.startup;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import uz.qoida.platform.telemetry.api.StreamChannel;

/**
 * Fails startup on a channel that declares no authorization (ADR 0045).
 *
 * <p>{@link StreamChannel}'s constructor already refuses a null capability, so
 * this can look redundant. It is not, and the difference is what a reviewer sees:
 * a constructor argument being load-bearing is invisible, and the next person
 * adding a channel reads the enum rather than its constructor. This runner is the
 * declaration that the rule exists and where it is enforced — the same shape ADR
 * 0033 uses for its cache registry and ADR 0030 for its configuration keys.
 *
 * <p>It also refuses a snapshot channel whose payload nobody classified. A
 * snapshot carries a bounded payload inline rather than an identifier, which is a
 * registered exception to signal-not-state, and an exception with no declared
 * classification is how a coordinate ends up on a channel that was reviewed as a
 * counter.
 */
@Component
public class StreamChannelRegistryCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StreamChannelRegistryCheck.class);

    /**
     * The snapshot channels ADR 0045 registers, with the payload class each was
     * reviewed as carrying. A snapshot channel absent from here fails startup.
     */
    private static final List<String> REGISTERED_SNAPSHOT_CHANNELS =
            List.of(StreamChannel.COUNTERS.name(), StreamChannel.COURIER_POSITIONS.name());

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    /** Separated from {@link #run} so a test can call it without a Spring context. */
    public static void verify() {
        List<String> problems = new ArrayList<>();

        for (StreamChannel channel : StreamChannel.values()) {
            if (channel.capability() == null) {
                problems.add(channel + " declares no capability");
            }
            if (channel.scopeTypes().isEmpty()) {
                problems.add(channel + " declares no scope type");
            }
            if (channel.frameClass() == StreamChannel.FrameClass.SNAPSHOT
                    && !REGISTERED_SNAPSHOT_CHANNELS.contains(channel.name())) {
                problems.add(channel + " carries a payload inline without being a registered "
                        + "snapshot channel with a declared classification");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The ADR 0045 stream channel catalogue is incomplete: " + problems);
        }
        log.debug("{} stream channels registered, each naming a capability",
                StreamChannel.values().length);
    }
}
