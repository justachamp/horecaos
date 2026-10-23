package uz.horecaos.platform.courier.infrastructure.notification;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger;

/**
 * The SMS gateway a laptop has: it accepts the message, remembers it, and tells the developer where the
 * link is (ADR 0178).
 *
 * <p>The {@code local} profile only. A real environment has no messenger until the owner connects the
 * gateway's {@code COURIER} purpose (ADR 0146), and an invitation then waits, loudly, rather than
 * pretending to be sent. This class is the external fact's stand-in and not its answer: nothing here
 * proves that a text reaches a handset.
 *
 * <p>The link is a bearer credential for setting a courier's password, so it reaches a log only here, on a
 * profile that has fixed one-time codes and no real courier in it.
 */
@Component
@Profile("local")
public class FakeCourierAccessMessenger implements CourierAccessMessenger {

    private static final Logger log = LoggerFactory.getLogger(FakeCourierAccessMessenger.class);

    private final List<AccessMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public Outcome send(AccessMessage message) {
        sent.add(message);
        log.info(
                "FAKE SMS (local profile): courier access {} {} would be texted this link: {}",
                message.kind(),
                message.invitationId(),
                message.link());
        return Outcome.accepted();
    }

    /** What this process has been asked to send, oldest first. */
    public List<AccessMessage> sent() {
        return List.copyOf(sent);
    }
}
