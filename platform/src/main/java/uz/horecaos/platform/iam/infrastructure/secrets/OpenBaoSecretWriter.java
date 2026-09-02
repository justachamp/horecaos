package uz.horecaos.platform.iam.infrastructure.secrets;

import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.iam.api.secrets.SecretWriter;

/**
 * Writes a secret into OpenBao's KV v2 API (ADR 0028, ADR 0065).
 *
 * <p>The write-side twin of {@link OpenBaoSecretResolver}: same plain-HTTP
 * discipline for the same reason ("the API surface used here is two endpoints,
 * and a library would put provider vocabulary ... into code that ADR 0034 phase
 * two migrates onto AWS Secrets Manager"), same path derivation
 * ({@link OpenBaoSecretResolver#pathFor}), and the same refusal to propagate a
 * failure response body — KV v2's error payload can echo request detail, and
 * this call's request body is a secret value.
 *
 * <p>A KV v2 write is a new version of the same logical secret, so writing twice
 * under one reference is exactly ADR 0028 rotation: "changes the value behind a
 * stable reference so no business row is rewritten". This class does not
 * distinguish create from rotate; the caller decides which reference to write.
 */
public class OpenBaoSecretWriter implements SecretWriter {

    private final RestClient client;
    private final String mount;

    public OpenBaoSecretWriter(RestClient client, String mount) {
        this.client = client;
        this.mount = mount;
    }

    @Override
    public void write(SecretReference reference, SecretValue value) {
        try {
            client.post()
                    .uri("/v1/{mount}/data/{path}", mount, OpenBaoSecretResolver.pathFor(reference))
                    .body(new KvWriteRequest(Map.of("value", value.reveal())))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, failure) -> {
                        // The reference is safe to name; the response body may
                        // echo request detail (including, on some backends, a
                        // truncated view of what was submitted) and is
                        // deliberately never read, let alone propagated.
                        throw new SecretWriteFailedException(reference);
                    })
                    .toBodilessEntity();
        } catch (SecretWriteFailedException already) {
            throw already;
        } catch (RuntimeException transportFailure) {
            // Never let a client exception carry the request body into a log
            // line or a message string; rethrow with only the reference named.
            throw new SecretWriteFailedException(reference);
        }
    }

    /** Thrown when OpenBao refuses or fails a write. Never carries the value. */
    public static final class SecretWriteFailedException extends RuntimeException {
        public SecretWriteFailedException(SecretReference reference) {
            super("Could not write a secret for " + reference);
        }
    }

    /** The subset of the KV v2 write request this adapter sends. */
    record KvWriteRequest(Map<String, String> data) {}
}
