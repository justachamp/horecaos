package uz.horecaos.platform.media.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * The platform must stay usable against object stores that are not S3 itself.
 *
 * <p>Recent SDK versions attach {@code x-amz-checksum-*} to every upload by
 * default. S3 accepts it; other implementations of the same API answer 400 and
 * do not degrade — a Ceph-backed store was measured refusing exactly this, which
 * would have failed the platform's first menu photo after a host move, every
 * time rather than intermittently.
 *
 * <p>This exercises {@link ObjectStorageConfiguration#s3Client} itself rather
 * than a client the test assembles, because a copy would keep passing after
 * somebody removed the setting from the real one.
 */
class ObjectStorageChecksumCompatibilityTests {

    private static final String ACCESS_REF = "horecaos:test:object_storage:platform:access-key";
    private static final String SECRET_REF = "horecaos:test:object_storage:platform:secret-key";

    /** Refuses anything carrying a checksum header, the way a stricter store does. */
    private HttpServer store;

    private final List<String> refusedHeaders = new ArrayList<>();

    private String endpoint = "";

    @BeforeEach
    void startStore() throws IOException {
        store = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        store.createContext("/", exchange -> {
            List<String> offending = exchange.getRequestHeaders().keySet().stream()
                    .filter(name -> {
                        String lower = name.toLowerCase(Locale.ROOT);
                        return lower.startsWith("x-amz-checksum-") || lower.equals("x-amz-sdk-checksum-algorithm");
                    })
                    .toList();
            exchange.getRequestBody().readAllBytes();
            if (!offending.isEmpty()) {
                refusedHeaders.addAll(offending);
                exchange.sendResponseHeaders(400, -1);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        store.start();
        endpoint = "http://127.0.0.1:" + store.getAddress().getPort();
    }

    @AfterEach
    void stopStore() {
        store.stop(0);
    }

    @Test
    void uploadsSucceedAgainstAStoreThatRefusesChecksumHeaders() {
        var client = new ObjectStorageConfiguration()
                .s3Client(
                        stubResolver(),
                        endpoint,
                        "us-east-1",
                        ACCESS_REF,
                        SECRET_REF,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(5));

        assertThatCode(() -> client.putObject(
                        PutObjectRequest.builder()
                                .bucket("horecaos-media")
                                .key("menu/dish.jpg")
                                .contentType("image/jpeg")
                                .build(),
                        RequestBody.fromBytes(new byte[] {1, 2, 3})))
                .as("an upload must not carry checksum headers a compliant-enough store rejects")
                .doesNotThrowAnyException();

        assertThat(refusedHeaders)
                .as("the store saw no checksum header at all, which is the property under test")
                .isEmpty();
    }

    private static SecretResolver stubResolver() {
        return new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of("probe-value");
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return resolve(reference);
            }
        };
    }
}
