package uz.horecaos.platform.web.idempotency;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * Decides whether an idempotency record may hold its response body in clear, and
 * encrypts it when it may not (ADR 0029 over ADR 0031).
 *
 * <p>{@code platform.idempotency_records.response_body} keeps the verbatim
 * response of every effectful endpoint for at least twenty-four hours. Most of
 * those responses are identifiers, statuses and money, and storing them plainly
 * is what makes a replay cheap and a stuck retry debuggable. Three of them
 * answer with a value the envelope stack had just decrypted, and for those the
 * column was undoing the encryption of the row it was copied from.
 *
 * <p><strong>The endpoint does not get a say.</strong> The decision is read off
 * the handler's own response type by {@link ClassificationScanner} — the same
 * classifier, annotations and name heuristic that already keep personal data off
 * Kafka topics. An author adding an endpoint that answers with an address gets
 * encryption without knowing this class exists, and an author who wanted the
 * body in clear cannot get it by forgetting something. That is deliberate: an
 * opt-out on {@link Idempotent} would have been narrower and honest, and it
 * would also have been one more thing to remember, which is the shape of defect
 * this table already demonstrated once.
 *
 * <p><strong>Why encrypt rather than store nothing.</strong> Dropping the body
 * for these endpoints would be cheaper and would satisfy the same audit, but it
 * changes what a replay promises. ADR 0031 answers a retry with the response the
 * first call produced; re-deriving it from the resource would answer with the
 * resource as it is now. On {@code PUT /me/addresses/{addressId}} — guarded by
 * If-Match precisely because a second tab is expected — those differ whenever an
 * edit landed in between, and a client retrying a timeout would receive somebody
 * else's later write, and its ETag, as the result of its own call.
 *
 * <p>The ciphertext is bound to the tenant and to the exact record it sits in,
 * so a body copied to another row or another tenant fails to decrypt instead of
 * replaying one caller's address to another.
 */
@Component
public class ResponseBodyProtection {

    /** Named for the AAD binding, which must match between the write and the replay. */
    static final String TABLE = "platform.idempotency_records";

    static final String COLUMN = "response_body";

    /**
     * A replay is a retransmission of an answer this caller already earned, not
     * a fresh look at somebody's data — so the purpose recorded says exactly
     * that. It is deliberately not the purpose the original reveal recorded:
     * attributing a network retry to "the customer viewed their address book"
     * would inflate the count that ADR 0027's control exists to make meaningful.
     */
    private static final String REPLAY_PURPOSE = "idempotency:replay-of-recorded-response";

    private final FieldProtection protection;

    /** Reflection over a response type is stable per handler, so it is done once. */
    private final Map<Method, Optional<DataClass>> classifications = new ConcurrentHashMap<>();

    public ResponseBodyProtection(FieldProtection protection) {
        this.protection = protection;
    }

    /**
     * The class to encrypt this handler's response under, or empty when the
     * response carries nothing classified.
     *
     * <p>The strongest class reachable from the response wins. Encrypting a
     * response that carries a passport number under the key for ordinary
     * personal data would file it below its classification, and the key is per
     * tenant and per class precisely so that difference is expressible.
     */
    public Optional<DataClass> classificationOf(HandlerMethod handler) {
        return classifications.computeIfAbsent(handler.getMethod(), ResponseBodyProtection::classify);
    }

    static Optional<DataClass> classify(Method handler) {
        Class<?> responseType = responseTypeOf(handler.getGenericReturnType());
        if (responseType == null) {
            return Optional.empty();
        }
        return ClassificationScanner.scan(responseType, responseType.getSimpleName()).stream()
                .map(ClassificationScanner.Finding::dataClass)
                .filter(DataClass::requiresEncryption)
                .max(Comparator.comparingInt(Enum::ordinal));
    }

    /**
     * The type a handler actually serialises, unwrapping the containers that
     * carry it.
     *
     * <p>{@code ResponseEntity<List<AddressResponse>>} is an address book, and a
     * check that stopped at {@code ResponseEntity} would find nothing classified
     * on any endpoint in the codebase and pass forever.
     */
    static Class<?> responseTypeOf(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (raw instanceof Class<?> rawClass && isContainer(rawClass)) {
                Type[] arguments = parameterized.getActualTypeArguments();
                return arguments.length == 0 ? null : responseTypeOf(arguments[arguments.length - 1]);
            }
            return responseTypeOf(raw);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] bounds = wildcard.getUpperBounds();
            return bounds.length == 0 ? null : responseTypeOf(bounds[0]);
        }
        if (type instanceof Class<?> candidate) {
            return candidate.isRecord() ? candidate : null;
        }
        return null;
    }

    private static boolean isContainer(Class<?> type) {
        return org.springframework.http.ResponseEntity.class.isAssignableFrom(type)
                || Iterable.class.isAssignableFrom(type)
                || Optional.class.isAssignableFrom(type);
    }

    /**
     * Whether a response type can be classified by reading it.
     *
     * <p>A record can. A {@code Map<String, Object>} cannot: nothing in its type
     * says what a handler will put in it. The interceptor treats an unscannable
     * response as unclassified, so this is the seam a build-time check has to
     * cover rather than trust — see the enforcement test.
     */
    public static boolean isScannable(Method handler) {
        return responseTypeOf(handler.getGenericReturnType()) != null || carriesNoBody(handler.getGenericReturnType());
    }

    static boolean carriesNoBody(Type type) {
        Class<?> raw = type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType()
                : type instanceof Class<?> candidate ? candidate : null;
        if (raw == null) {
            return false;
        }
        if (raw == void.class || raw == Void.class) {
            return true;
        }
        if (org.springframework.http.ResponseEntity.class.isAssignableFrom(raw)
                && type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            return arguments.length == 1 && carriesNoBody(arguments[0]);
        }
        return raw == UUID.class || raw == String.class || Number.class.isAssignableFrom(raw) || raw.isPrimitive();
    }

    /** Encrypts a body for storage, bound to the tenant and the record holding it. */
    public String protect(UUID tenantId, DataClass dataClass, UUID recordId, String body) {
        return protection
                .protect(tenantId, dataClass, recordRef(recordId), body)
                .serialize();
    }

    /** Reverses {@link #protect} for a replay. */
    public String reveal(UUID tenantId, UUID recordId, String stored) {
        return protection.reveal(tenantId, ProtectedValue.deserialize(stored), recordRef(recordId), REPLAY_PURPOSE);
    }

    private static FieldProtection.RecordRef recordRef(UUID recordId) {
        return new FieldProtection.RecordRef(TABLE, COLUMN, recordId);
    }

    /** The handlers whose responses are classified, for the enforcement test to name. */
    static List<String> describe(Method handler) {
        return classify(handler)
                .map(dataClass -> List.of(
                        handler.getDeclaringClass().getSimpleName() + "#" + handler.getName() + " -> " + dataClass))
                .orElseGet(List::of);
    }
}
