package uz.horecaos.platform.support.printing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import uz.horecaos.platform.support.printing.EscPosDecoder.Printed;

/**
 * A printer on a TCP port (ADR 0154): a listener that records the bytes written to it and decodes the raster, so
 * every test above the wire runs without hardware. What the reference agent does to a real printer on port 9100
 * -- open a socket, write the ESC/POS, close -- is what a test does to this.
 *
 * <p>Each connection is one page. It binds to the loopback interface on a port the operating system chooses, so
 * it can never collide with the 9100 of a real one or another test's.
 */
public final class FakePrinter implements AutoCloseable {

    private final ServerSocket server;
    private final List<byte[]> pages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread acceptor;

    public FakePrinter() {
        try {
            this.server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        this.acceptor =
                Thread.ofPlatform().daemon().name("fake-printer-" + port()).start(this::accept);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!closed.get()) {
            try (Socket connection = server.accept()) {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                InputStream in = connection.getInputStream();
                in.transferTo(received);
                pages.add(received.toByteArray());
            } catch (IOException ended) {
                if (!closed.get()) {
                    throw new UncheckedIOException(ended);
                }
            }
        }
    }

    /** What an agent does to a printer: connect, write the bytes, close. */
    public static void send(int port, byte[] bytes) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The raw bytes of every page received so far. */
    public List<byte[]> pages() {
        return List.copyOf(pages);
    }

    /** Waits until {@code count} pages have been received. */
    public void awaitPages(int count, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (pages.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Expected " + count + " page(s) within " + within + ", got " + pages.size());
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for a page", interrupted);
            }
        }
    }

    /** The last page, decoded as the printer would draw it. */
    public Printed lastPage() {
        if (pages.isEmpty()) {
            throw new AssertionError("The printer has received nothing");
        }
        return EscPosDecoder.decode(pages.getLast());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                server.close();
            } catch (IOException ignored) {
                // the acceptor is daemon and is released with the socket
            }
            acceptor.interrupt();
        }
    }
}
