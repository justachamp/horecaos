package uz.horecaos.platform.integration.provider.voice.asterisk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * A fake Asterisk AMI endpoint, in the {@code ControlledFakeProvider}/{@code
 * FakeClickHttpProvider} genre (ADR 0007): plain JDK sockets, no Spring, no
 * scenario switch reachable from production code, started on an ephemeral
 * port, with a {@link #pushEvent} hook a test uses to simulate whatever
 * Asterisk itself would fire.
 *
 * <p>Unlike {@code FakeClickHttpProvider}, this fake is a server the real
 * client dials <em>out</em> to — the direction ADR 0064's Asterisk-class
 * adapter actually connects — rather than one this application's own code
 * calls into.
 *
 * <p>Accepts exactly one connection. Reads and validates the AMI {@code
 * Action: Login} block, responds {@code Response: Success} or {@code
 * Response: Error}, and then only writes — everything after login is this
 * fake pushing events, never reading a further client action, matching what
 * {@link AsteriskAmiSocketClient} actually sends after it has logged in.
 */
final class FakeAsteriskAmiServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final String expectedUsername;
    private final String expectedSecret;
    private final CountDownLatch loggedIn = new CountDownLatch(1);
    private volatile @Nullable Socket clientSocket;
    private volatile @Nullable OutputStream clientOut;

    private FakeAsteriskAmiServer(ServerSocket serverSocket, String expectedUsername, String expectedSecret) {
        this.serverSocket = serverSocket;
        this.expectedUsername = expectedUsername;
        this.expectedSecret = expectedSecret;
        this.executor = Executors.newSingleThreadExecutor(daemonThreadFactory());
    }

    static FakeAsteriskAmiServer start(String expectedUsername, String expectedSecret) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        FakeAsteriskAmiServer server = new FakeAsteriskAmiServer(serverSocket, expectedUsername, expectedSecret);
        var unused = server.executor.submit(server::acceptAndLogin);
        return server;
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void awaitLogin(long timeoutSeconds) throws InterruptedException {
        if (!loggedIn.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No client logged in within " + timeoutSeconds + "s");
        }
    }

    /** Writes one raw AMI event block, exactly as Asterisk would push it to a connected manager. */
    void pushEvent(Map<String, String> fields) throws IOException {
        OutputStream out = clientOut;
        if (out == null) {
            throw new IllegalStateException("No client is connected yet");
        }
        StringBuilder block = new StringBuilder();
        fields.forEach(
                (key, value) -> block.append(key).append(": ").append(value).append("\r\n"));
        block.append("\r\n");
        out.write(block.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    void closeClientConnection() throws IOException {
        Socket socket = clientSocket;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        serverSocket.close();
    }

    private void acceptAndLogin() {
        try {
            Socket socket = serverSocket.accept();
            clientSocket = socket;
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            clientOut = out;

            Optional<Map<String, String>> login = AsteriskAmiEventParser.readBlock(reader);
            boolean authenticated = login.isPresent()
                    && expectedUsername.equals(login.get().get("Username"))
                    && expectedSecret.equals(login.get().get("Secret"));

            String response = authenticated
                    ? "Response: Success\r\nMessage: Authentication accepted\r\n\r\n"
                    : "Response: Error\r\nMessage: Authentication failed\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();

            if (authenticated) {
                loggedIn.countDown();
            }
        } catch (IOException connectionClosed) {
            // The test closed the server, or the client disconnected — either
            // way there is nothing left for this fake to do.
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "fake-asterisk-ami");
            thread.setDaemon(true);
            return thread;
        };
    }
}
