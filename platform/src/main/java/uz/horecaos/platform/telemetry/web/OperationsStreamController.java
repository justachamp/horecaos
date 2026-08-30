package uz.horecaos.platform.telemetry.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.telemetry.api.RealtimeSignal.Subscription;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry;
import uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry.Connection;
import uz.horecaos.platform.telemetry.infrastructure.realtime.SseStreamRegistry.StreamCapReachedException;
import uz.horecaos.platform.telemetry.infrastructure.realtime.StreamSink;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * The one streaming endpoint (ADR 0045).
 *
 * <p><strong>The path is not the one ADR 0045 sketches, and for the reason ADR
 * 0041's kitchen board records.</strong> The ADR writes
 * {@code GET /api/v1/operations/streams?scope=LOCATION:{locationId}}, and ADR
 * 0025's scope resolution reads its identifiers from path variables: a flat path
 * cannot express a {@code LOCATION} scope at all, and the ADR 0025 build gate
 * refuses an endpoint whose declared scope is wider than its path. The query
 * parameter survives as the subscription's scope key, which is what a signal is
 * routed by — and a key naming a branch this connection was not opened at is
 * refused before any capability is even consulted.
 *
 * <p><strong>The subscription set is fixed for the connection's life.</strong>
 * Changing it means reconnecting. A mutable subscription needs an upstream
 * channel, and an upstream channel is the WebSocket argument coming back through
 * a side door — every mutation these surfaces send is an ADR 0031 request with an
 * {@code Idempotency-Key}, a capability check, an expected version, and an audit
 * record, and a socket frame would route around all four.
 *
 * <p><strong>Every channel is authorized separately, at its own scope.</strong>
 * The endpoint's own declaration is {@code location.read}, which is the right to
 * be looking at this branch at all; it is not the right to see its orders, its
 * stop list, or its couriers. Each of those is the channel's declared capability,
 * checked here and re-checked by the registry closing the stream whenever grants
 * change.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/operations")
@Tag(name = "Operational streams", description = "Server-Sent Events for the live operations surfaces")
public class OperationsStreamController {

    /**
     * How long the servlet holds the async request open before timing it out.
     *
     * <p>Above any token lifetime the platform issues, because the stream should
     * close for an authorization reason with a {@code closing} frame the client
     * can read, not for a servlet reason with a silent socket reset.
     */
    private static final Duration ASYNC_TIMEOUT = Duration.ofHours(12);

    /** What a token with no expiry claim is treated as. Deliberately short. */
    private static final Duration UNKNOWN_TOKEN_LIFETIME = Duration.ofMinutes(15);

    private final SseStreamRegistry registry;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public OperationsStreamController(SseStreamRegistry registry, AuthorizationService authorization,
            CurrentActor currentActor, RateLimiter rateLimiter, Clock clock) {
        this.registry = registry;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @GetMapping(value = "/streams", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "Open a stream of operational signals",
            description = "A frame carries a signal, not state: the client is told that something "
                    + "in its subscribed scope changed and re-reads it through the ordinary "
                    + "authorized API. Two channels are registered exceptions and carry a bounded "
                    + "payload inline. There is no replay buffer — a reconnect with Last-Event-Id "
                    + "receives a resync frame telling it to re-read its whole scope. Every "
                    + "surface here also has a polling path that must work; this is an "
                    + "accelerator on top of it, and turning it off changes no other code.")
    public SseEmitter open(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "channels") List<String> channels,
            @RequestHeader(name = "Last-Event-Id", required = false) String lastEventId,
            @AuthenticationPrincipal Jwt token) {

        String subject = currentActor.get().subject();

        // Connect is rate limited per principal and per tenant. A deploy drops
        // every stream on the box at once and every client reconnects at once;
        // jittered reconnect bounds that herd and this refuses what is left of it.
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key("realtime.stream.connect", tenantId.toString(), subject),
                RateLimiter.Policy.perMinute(30));
        if (!decision.allowed()) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many stream connects; reconnect after a jittered delay",
                    Map.of("retryAfterSeconds", decision.retryAfter().toSeconds()));
        }

        ScopeKey requested = parseScope(scope, locationId);
        Set<Subscription> subscriptions = authorize(
                tenantId, brandId, locationId, subject, requested, channels);

        SseEmitter emitter = new SseEmitter(ASYNC_TIMEOUT.toMillis());
        Connection connection;
        try {
            connection = registry.open(tenantId, subject, subscriptions, sinkFor(emitter),
                    tokenExpiry(token), lastEventId);
        } catch (StreamCapReachedException capped) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, capped.getMessage());
        }

        // Every path out of a stream removes it from the registry. A connection
        // that stays in the map after its socket died is a leak that looks like
        // headroom until the cap refuses a real client.
        emitter.onCompletion(() -> registry.close(connection.id()));
        emitter.onTimeout(() -> registry.close(connection.id()));
        emitter.onError(failure -> registry.close(connection.id()));

        return emitter;
    }

    /**
     * Turns the requested channels into subscriptions, refusing each one the
     * principal may not have.
     *
     * <p>An unknown channel is a {@code 400} rather than a silently ignored
     * string: a client that misspells {@code stop_list} and receives an open
     * stream with nothing on it has a bug that looks like an empty kitchen.
     */
    private Set<Subscription> authorize(UUID tenantId, UUID brandId, UUID locationId,
            String subject, ScopeKey requested, List<String> channels) {

        if (channels.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A stream subscribes to at least one channel");
        }
        Set<Subscription> subscriptions = new LinkedHashSet<>();

        for (String name : channels) {
            StreamChannel channel;
            try {
                channel = StreamChannel.require(name);
            } catch (StreamChannel.UnknownChannelException unknown) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, unknown.getMessage(),
                        Map.of("channel", name));
            }

            ScopeKey scopeKey = requested;
            if (!channel.isSubscribableAt(scopeKey.type())) {
                // A tenant-wide channel asked for at a location, or the reverse.
                // Resolve it to the level the catalogue declares, using this
                // connection's own identifiers so nothing widens.
                scopeKey = switch (channel.scopeTypes().iterator().next()) {
                    case TENANT -> ScopeKey.tenant(tenantId);
                    case BRAND -> ScopeKey.brand(brandId);
                    case LOCATION -> ScopeKey.location(locationId);
                    case PLATFORM -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "No channel is carried at platform scope");
                };
            }

            ResourceScope scope = scopeKey.authorizationScope(tenantId, brandId, locationId);
            authorization.require(subject, channel.capability(), scope);
            subscriptions.add(new Subscription(channel, scopeKey));
        }
        return subscriptions;
    }

    private static ScopeKey parseScope(String scope, UUID locationId) {
        if (scope == null || scope.isBlank()) {
            return ScopeKey.location(locationId);
        }
        try {
            return ScopeKey.parse(scope);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage(),
                    Map.of("parameter", "scope"));
        }
    }

    /**
     * When this stream stops being authorized.
     *
     * <p>ADR 0045 is blunt about why this matters: a connection held open all
     * shift on a token that expired in five minutes is an authorization hole that
     * looks like a working feature.
     */
    private Instant tokenExpiry(Jwt token) {
        if (token == null || token.getExpiresAt() == null) {
            return clock.instant().plus(UNKNOWN_TOKEN_LIFETIME);
        }
        return token.getExpiresAt();
    }

    /**
     * Adapts the emitter to the registry's sink.
     *
     * <p>{@code SseEmitter} is returned from the handler, so Spring MVC dispatches
     * the request asynchronously and releases the container worker thread — which
     * is the binding constraint in ADR 0045's cost table and the one that is
     * invisible until it is not. A blocking write loop here would pin a thread per
     * connection and exhaust the pool at a couple of hundred streams.
     */
    private static StreamSink sinkFor(SseEmitter emitter) {
        return new StreamSink() {

            @Override
            public void send(String eventName, String id, String data) {
                try {
                    emitter.send(SseEmitter.event().name(eventName).id(id).data(data));
                } catch (IOException | IllegalStateException gone) {
                    // The registry treats this as a dropped client and removes it.
                    throw new SinkClosedException(gone);
                }
            }

            @Override
            public void heartbeat() {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException gone) {
                    throw new SinkClosedException(gone);
                }
            }

            @Override
            public void complete() {
                try {
                    emitter.complete();
                } catch (RuntimeException alreadyGone) {
                    // Completing a completed emitter is not an event worth a log
                    // line: the ordinary cause is a browser tab that closed.
                }
            }

            @Override
            public void completeWithError(Throwable failure) {
                try {
                    emitter.completeWithError(failure);
                } catch (RuntimeException alreadyGone) {
                    // As above.
                }
            }
        };
    }

    /** A client that went away mid-write. Ordinary, and never an incident. */
    static final class SinkClosedException extends RuntimeException {
        SinkClosedException(Throwable cause) {
            super(cause);
        }
    }
}
