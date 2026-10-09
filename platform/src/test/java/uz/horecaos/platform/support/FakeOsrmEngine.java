package uz.horecaos.platform.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An OSRM {@code route} service on a loopback port, answering what the real engine
 * answers (ADR 0147).
 *
 * <p>The bodies are the shapes {@code osrm-routed} produces for
 * {@code overview=false&steps=false}: a {@code code}, one route with a {@code distance}
 * in metres and a {@code duration} in seconds, and the snapped waypoints. A {@code
 * NoRoute} answer is HTTP 400, which is what the real engine does and what makes
 * "no route" arrive through the client's rejected path rather than its success one.
 *
 * <p>Recorded, not a mock of the adapter: the adapter talks to it over real HTTP through
 * the real {@code ProviderHttpClient}, so a wrong path, a swapped coordinate order or a
 * missing deadline fails here. What it cannot prove is the engine's own routing, and
 * nothing in this class pretends to: the figures are whatever a test says.
 */
public final class FakeOsrmEngine implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    /**
     * How far a pin that is on the network typically sits from the road, as the real engine
     * reports it in each waypoint's {@code distance}: a few metres, never exactly zero.
     */
    private static final double ORIGIN_SNAP_METERS = 3.2;

    private static final double DESTINATION_SNAP_METERS = 1.9;

    private final AtomicReference<Mode> mode =
            new AtomicReference<>(new Mode.Route(4_321.7, 468.2, ORIGIN_SNAP_METERS, DESTINATION_SNAP_METERS));

    private FakeOsrmEngine(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static FakeOsrmEngine start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            // Daemon threads: a handler parked in slow() must not keep the test JVM alive.
            ExecutorService executor = Executors.newFixedThreadPool(8, task -> {
                Thread thread = new Thread(task, "fake-osrm");
                thread.setDaemon(true);
                return thread;
            });
            FakeOsrmEngine engine = new FakeOsrmEngine(server, executor);
            server.setExecutor(executor);
            server.createContext("/route/v1/driving/", engine::handle);
            server.start();
            return engine;
        } catch (IOException failure) {
            throw new IllegalStateException("The fake OSRM engine could not start", failure);
        }
    }

    /** The base URL an approved environment would hold. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The port, for a test that wants the engine to be unreachable once it has stopped. */
    public int port() {
        return server.getAddress().getPort();
    }

    public int hits() {
        return hits.get();
    }

    /** Every path-and-query the engine was asked, in order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public String lastRequest() {
        return requests.isEmpty() ? "" : requests.getLast();
    }

    public void routeOf(double meters, double seconds) {
        mode.set(new Mode.Route(meters, seconds, ORIGIN_SNAP_METERS, DESTINATION_SNAP_METERS));
    }

    /**
     * A route whose two pins are not on the road: each waypoint's {@code distance} is how far
     * the engine had to move the requested coordinate to reach the network (a courtyard, a
     * field), which is walked and is not in the route's own {@code distance}.
     */
    public void routeOf(double meters, double seconds, double originSnapMeters, double destinationSnapMeters) {
        mode.set(new Mode.Route(meters, seconds, originSnapMeters, destinationSnapMeters));
    }

    /** HTTP 200 with a route and no {@code waypoints}, which the real engine never sends for a route. */
    public void routeWithoutWaypoints() {
        mode.set(new Mode.Status(
                200,
                "{\"code\":\"Ok\",\"routes\":[{\"legs\":[],\"weight_name\":\"routability\","
                        + "\"weight\":468.2,\"duration\":468.2,\"distance\":4321.7}]}"));
    }

    /** HTTP 400 {@code NoRoute}, which is how the real engine says two points are not connected. */
    public void noRoute() {
        mode.set(new Mode.NoRoute("NoRoute", "Impossible route between points"));
    }

    /** HTTP 400 {@code NoSegment}: a coordinate further from a road than the request allowed. */
    public void noSegment() {
        mode.set(new Mode.NoRoute("NoSegment", "Could not find a matching segment for coordinate 1"));
    }

    public void slow(Duration delay) {
        mode.set(new Mode.Slow(delay));
    }

    public void serverError() {
        mode.set(new Mode.Status(500, "{\"code\":\"InternalError\",\"message\":\"boom\"}"));
    }

    /** HTTP 200 with a body that is JSON and is not a route. */
    public void garbage() {
        mode.set(new Mode.Status(200, "{\"code\":\"Ok\",\"routes\":[]}"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        requests.add(exchange.getRequestURI().getRawPath() + "?"
                + exchange.getRequestURI().getRawQuery());
        Mode current = Objects.requireNonNull(mode.get());
        switch (current) {
            case Mode.Route route -> respond(exchange, 200, routeBody(route));
            case Mode.NoRoute none ->
                respond(exchange, 400, "{\"code\":\"" + none.code() + "\",\"message\":\"" + none.message() + "\"}");
            case Mode.Status status -> respond(exchange, status.status(), status.body());
            case Mode.Slow slow -> {
                try {
                    Thread.sleep(slow.delay().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    respond(
                            exchange,
                            200,
                            routeBody(new Mode.Route(1_000, 100, ORIGIN_SNAP_METERS, DESTINATION_SNAP_METERS)));
                } catch (IOException clientGaveUp) {
                    // The adapter's deadline passed and it closed the connection, which is the point.
                    exchange.close();
                }
            }
        }
    }

    private static String routeBody(Mode.Route route) {
        return "{\"code\":\"Ok\",\"routes\":[{\"legs\":[{\"steps\":[],\"weight\":%s,\"summary\":\"\",\"duration\":%s,\"distance\":%s}],"
                        .formatted(route.seconds(), route.seconds(), route.meters())
                + "\"weight_name\":\"routability\",\"weight\":%s,\"duration\":%s,\"distance\":%s}],"
                        .formatted(route.seconds(), route.seconds(), route.meters())
                + "\"waypoints\":[{\"hint\":\"h1\",\"location\":[69.2405,41.3110],\"name\":\"\",\"distance\":%s},"
                        .formatted(route.originSnapMeters())
                + "{\"hint\":\"h2\",\"location\":[69.2641,41.3309],\"name\":\"\",\"distance\":%s}]}"
                        .formatted(route.destinationSnapMeters());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private sealed interface Mode {
        record Route(double meters, double seconds, double originSnapMeters, double destinationSnapMeters)
                implements Mode {}

        record NoRoute(String code, String message) implements Mode {}

        record Status(int status, String body) implements Mode {}

        record Slow(Duration delay) implements Mode {}
    }
}
