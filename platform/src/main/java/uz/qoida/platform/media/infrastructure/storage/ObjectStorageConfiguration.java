package uz.qoida.platform.media.infrastructure.storage;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import uz.qoida.platform.iam.api.secrets.SecretCategory;
import uz.qoida.platform.iam.api.secrets.SecretReference;
import uz.qoida.platform.iam.api.secrets.SecretResolver;

/**
 * Object-store clients (ADR 0010), pointed at MinIO locally and an S3 API in
 * production.
 *
 * <p>Credentials come from ADR 0028's resolver rather than from properties, so a
 * rotation is a secrets-manager operation rather than a redeploy. They are read
 * once at startup because the SDK client holds them; a rotation therefore needs
 * a restart, which is recorded as a known limitation rather than hidden.
 */
@Configuration
public class ObjectStorageConfiguration {

    @Bean
    S3Client s3Client(SecretResolver secrets,
            @Value("${qoida.media.endpoint:http://localhost:9000}") String endpoint,
            @Value("${qoida.media.region:us-east-1}") String region,
            @Value("${qoida.media.access-key-reference}") String accessKeyReference,
            @Value("${qoida.media.secret-key-reference}") String secretKeyReference,
            @Value("${qoida.media.call-timeout:15s}") Duration callTimeout,
            @Value("${qoida.media.call-attempt-timeout:5s}") Duration attemptTimeout) {

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        secrets.resolve(require(accessKeyReference)).reveal(),
                        secrets.resolve(require(secretKeyReference)).reveal())))
                // Stated rather than inherited. Left to the SDK's defaults, one
                // call to a degraded store is a ~30-second socket timeout times
                // up to three attempts, and the worst case a caller has to plan
                // for is a minute and a half it was never told about. Fifteen
                // seconds is the whole call including retries; five bounds one
                // attempt, so a store that has stopped answering is discovered
                // twice over before the caller gives up.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(callTimeout)
                        .apiCallAttemptTimeout(attemptTimeout)
                        .build())
                // MinIO serves path-style addressing; virtual-host style would
                // require per-bucket DNS that does not exist locally.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    S3Presigner s3Presigner(SecretResolver secrets,
            @Value("${qoida.media.endpoint:http://localhost:9000}") String endpoint,
            @Value("${qoida.media.region:us-east-1}") String region,
            @Value("${qoida.media.access-key-reference}") String accessKeyReference,
            @Value("${qoida.media.secret-key-reference}") String secretKeyReference) {

        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        secrets.resolve(require(accessKeyReference)).reveal(),
                        secrets.resolve(require(secretKeyReference)).reveal())))
                // No call timeouts here, and none are missing: presigning is
                // local arithmetic over the credential and the request, and this
                // client never opens a socket.
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    /**
     * Parses a reference and insists it is an object-storage one.
     *
     * <p>Checked at startup rather than at first upload: a reference pointing at
     * the wrong category would otherwise surface as a puzzling auth failure the
     * first time somebody tried to add a photo to a menu.
     */
    static SecretReference require(String reference) {
        SecretReference parsed = SecretReference.parse(reference);
        if (parsed.category() != SecretCategory.OBJECT_STORAGE) {
            throw new IllegalStateException(
                    "Media credentials must use the object_storage category, got " + parsed.category());
        }
        return parsed;
    }
}
