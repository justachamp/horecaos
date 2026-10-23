package uz.horecaos.platform.mail.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;

/**
 * Which phase of an SMTP session a lost connection belongs to decides whether a message may be sent
 * again (ADR 0182, ADR 0097), so it is asserted against a real socket that misbehaves at a chosen
 * point rather than against a guess at what Spring's exceptions look like.
 *
 * <p>The rule under test, in the order a session runs: until the server has accepted the message's
 * data nothing can have been delivered, so a lost connection there is {@code SMTP_UNAVAILABLE} and a
 * retry is safe; once the data terminator has gone out and the final reply has not come, the server
 * may have queued the message, so it is {@code SMTP_NO_ANSWER} and a caller that must not send twice
 * must not send again; once the server has answered 250 the message is {@code Sent} whatever happens
 * to the goodbye. Needs no Docker: the server is a few lines of sockets in this class.
 */
class SmtpPlatformMailerWireFailureTests {

    private static final SecretResolver NO_SECRETS = new EnvironmentSecretResolver(key -> null, Clock.systemUTC());

    private static final MailOutcome UNAVAILABLE = new MailOutcome.Failed("SMTP_UNAVAILABLE");
    private static final MailOutcome NO_ANSWER = new MailOutcome.Failed(MailOutcome.SMTP_NO_ANSWER);

    /** Where in the session the server stops behaving. */
    private enum At {
        CONNECT,
        EHLO,
        MAIL,
        RCPT,
        DATA_COMMAND,
        AFTER_DATA_TERMINATOR,
        AFTER_ACCEPTING_REPLY
    }

    /** How it stops: a clean close, an abortive close (a TCP reset), silence, or a clear refusal. */
    private enum Fault {
        CLOSE,
        RESET,
        STALL,
        REFUSE_TEMPORARILY
    }

    private @Nullable FakeSmtpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("a server that drops or resets the connection before it greets is a failure worth retrying")
    void aConnectionLostBeforeTheGreetingIsRetryable() {
        assertThat(outcomeWhen(At.CONNECT, Fault.CLOSE)).isEqualTo(UNAVAILABLE);
        stopServer();
        assertThat(outcomeWhen(At.CONNECT, Fault.RESET)).isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("a reset or a close during EHLO is retryable, though the failure carries a cause and a reset")
    void aConnectionLostDuringEhloIsRetryable() {
        assertThat(outcomeWhen(At.EHLO, Fault.RESET)).isEqualTo(UNAVAILABLE);
        stopServer();
        assertThat(outcomeWhen(At.EHLO, Fault.CLOSE)).isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("a server that hangs up before it has been given the message's data has been sent nothing")
    void aServerThatClosesBeforeDataHasAcceptedNothing() {
        for (At phase : new At[] {At.MAIL, At.RCPT, At.DATA_COMMAND}) {
            stopServer();
            assertThat(outcomeWhen(phase, Fault.CLOSE))
                    .as("the server closed at %s, before any of the message was transmitted", phase)
                    .isEqualTo(UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("a reset mid-session names no command, so it is uncertain: a lost send is cheaper than a duplicate")
    void aResetMidSessionIsUncertain() {
        for (At phase : new At[] {At.MAIL, At.RCPT, At.DATA_COMMAND}) {
            stopServer();
            assertThat(outcomeWhen(phase, Fault.RESET))
                    .as("a reset at %s carries no command to tell the phases apart", phase)
                    .isEqualTo(NO_ANSWER);
        }
    }

    @Test
    @DisplayName("a connection closed or reset after the data terminator is uncertain, and is never retried")
    void aConnectionLostAfterTheDataIsUncertain() {
        assertThat(outcomeWhen(At.AFTER_DATA_TERMINATOR, Fault.CLOSE)).isEqualTo(NO_ANSWER);
        stopServer();
        assertThat(outcomeWhen(At.AFTER_DATA_TERMINATOR, Fault.RESET)).isEqualTo(NO_ANSWER);
    }

    @Test
    @DisplayName("a server that takes the data and then says nothing until the read times out is uncertain")
    void aReadTimeoutAfterTheDataIsUncertain() {
        assertThat(outcomeWhen(At.AFTER_DATA_TERMINATOR, Fault.STALL)).isEqualTo(NO_ANSWER);
    }

    @Test
    @DisplayName("a server that answers the data with a clear temporary refusal has accepted nothing: retryable")
    void aTemporaryRefusalOfTheDataIsRetryable() {
        assertThat(outcomeWhen(At.AFTER_DATA_TERMINATOR, Fault.REFUSE_TEMPORARILY))
                .isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("a message the server accepted is sent, whatever becomes of the goodbye that follows")
    void anAcceptedMessageIsSentWhateverHappensToTheGoodbye() {
        assertThat(outcomeWhen(At.AFTER_ACCEPTING_REPLY, Fault.RESET)).isEqualTo(new MailOutcome.Sent());
        stopServer();
        assertThat(outcomeWhen(At.AFTER_ACCEPTING_REPLY, Fault.CLOSE)).isEqualTo(new MailOutcome.Sent());
    }

    @Test
    @DisplayName("Spring's failure to close after every message was accepted does not make the message a failure")
    void aFailureToCloseAfterAcceptanceIsNotAFailureToSend() {
        MailSendException closeFailed = new MailSendException(
                "Failed to close server connection after message sending",
                new SocketException("Connection reset"),
                new LinkedHashMap<>());

        assertThat(SmtpPlatformMailer.outcomeOf(closeFailed)).isEqualTo(new MailOutcome.Sent());
    }

    @Test
    @DisplayName("Spring's connection failure is retryable even when its cause is a read timeout")
    void aConnectionFailureIsRetryableWhateverItsCause() {
        SocketTimeoutException timedOut = new SocketTimeoutException("Read timed out");
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put("message", timedOut);

        assertThat(SmtpPlatformMailer.outcomeOf(
                        new MailSendException("Mail server connection failed", timedOut, failed)))
                .isEqualTo(UNAVAILABLE);
    }

    @Test
    @DisplayName("a read timeout that Spring reports as a message's own failure is uncertain")
    void aMessageFailureThatIsATimeoutIsUncertain() {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put(
                "message",
                new jakarta.mail.MessagingException(
                        "Exception reading response", new SocketTimeoutException("Read timed out")));

        assertThat(SmtpPlatformMailer.outcomeOf(new MailSendException(failed))).isEqualTo(NO_ANSWER);
    }

    // ----------------------------------------------------------------------------------------

    private MailOutcome outcomeWhen(At at, Fault fault) {
        FakeSmtpServer started = new FakeSmtpServer(at, fault);
        server = started;
        SmtpPlatformMailer mailer = new SmtpPlatformMailer(
                NO_SECRETS, "127.0.0.1", started.port(), "NONE", "", "", "HorecaOS <no-reply@horecaos.test>");
        return mailer.send(new OutgoingMail("guest@example.uz", "Chegirma", "Chegirma 20%", "<p>Chegirma 20%</p>"));
    }

    /**
     * One connection's worth of SMTP, up to the point it is told to go wrong. It answers the
     * commands a real relay answers, in the order a real client sends them, and nothing else.
     */
    private static final class FakeSmtpServer implements AutoCloseable {

        private final ServerSocket listener;
        private final At at;
        private final Fault fault;
        private final CountDownLatch finished = new CountDownLatch(1);

        FakeSmtpServer(At at, Fault fault) {
            this.at = at;
            this.fault = fault;
            try {
                this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            } catch (IOException cannotListen) {
                throw new IllegalStateException(cannotListen);
            }
            Thread thread = new Thread(this::serve, "fake-smtp");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void serve() {
            try (Socket socket = listener.accept()) {
                if (at == At.CONNECT) {
                    misbehave(socket);
                    return;
                }
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = socket.getOutputStream();
                reply(out, "220 fake ESMTP");
                boolean inData = false;
                String line;
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (!line.equals(".")) {
                            continue;
                        }
                        inData = false;
                        if (at == At.AFTER_DATA_TERMINATOR) {
                            if (fault == Fault.REFUSE_TEMPORARILY) {
                                reply(out, "451 try again later");
                                continue;
                            }
                            misbehave(socket);
                            return;
                        }
                        reply(out, "250 queued");
                        if (at == At.AFTER_ACCEPTING_REPLY) {
                            misbehave(socket);
                            return;
                        }
                        continue;
                    }
                    String command = line.toUpperCase(Locale.ROOT);
                    if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                        if (at == At.EHLO) {
                            misbehave(socket);
                            return;
                        }
                        reply(out, "250 fake");
                    } else if (command.startsWith("MAIL")) {
                        if (at == At.MAIL) {
                            misbehave(socket);
                            return;
                        }
                        reply(out, "250 ok");
                    } else if (command.startsWith("RCPT")) {
                        if (at == At.RCPT) {
                            misbehave(socket);
                            return;
                        }
                        reply(out, "250 ok");
                    } else if (command.startsWith("DATA")) {
                        if (at == At.DATA_COMMAND) {
                            misbehave(socket);
                            return;
                        }
                        reply(out, "354 go ahead");
                        inData = true;
                    } else if (command.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        return;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException | InterruptedException sessionEnded) {
                // The client going away is how every one of these sessions ends.
            } finally {
                finished.countDown();
            }
        }

        /** Stops behaving the way {@link #fault} says; closing the socket afterwards is the caller's try. */
        private void misbehave(Socket socket) throws IOException, InterruptedException {
            switch (fault) {
                case CLOSE -> {
                    // A FIN: the client's next read sees the end of the stream.
                }
                case RESET -> socket.setSoLinger(true, 0); // closing now sends a RST
                case STALL -> finished.await(30, TimeUnit.SECONDS);
                case REFUSE_TEMPORARILY -> throw new IllegalStateException("handled where the reply is due");
            }
        }

        private static void reply(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() {
            finished.countDown();
            try {
                listener.close();
            } catch (IOException alreadyClosed) {
                // Nothing left to release.
            }
        }
    }
}
