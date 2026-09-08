package uz.horecaos.platform.audit.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import uz.horecaos.platform.audit.api.AuditArchiveStore;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;

/**
 * The object-store client for closed audit partitions (ADR 0027, ADR 0073).
 *
 * <p>A separate client and a separate bucket from {@code media}'s, deliberately.
 * The two modules point at the same physical provider by default — one
 * S3-compatible account, two buckets, matching how {@code infra/backup} already
 * keeps its bucket apart from {@code media}'s — but {@code audit} owns its own
 * client rather than reaching into {@code media.infrastructure.storage}'s
 * internal bean. A second bare {@code S3Client} bean would also make Spring's
 * autowiring of {@code media}'s own client ambiguous, which is reason enough on
 * its own: the client built here never leaves this package as a bean of its own,
 * only wrapped inside {@link S3AuditArchiveStore}.
 */
@Configuration
public class AuditArchiveStorageConfiguration {

    @Bean
    AuditArchiveStore auditArchiveStore(
            SecretResolver secrets,
            @Value("${horecaos.audit.archive.endpoint:http://localhost:9000}") String endpoint,
            @Value("${horecaos.audit.archive.region:us-east-1}") String region,
            @Value("${horecaos.audit.archive.bucket:horecaos-audit-archive}") String bucket,
            @Value("${horecaos.audit.archive.access-key-reference}") String accessKeyReference,
            @Value("${horecaos.audit.archive.secret-key-reference}") String secretKeyReference,
            @Value("${horecaos.audit.archive.call-timeout:30s}") Duration callTimeout,
            @Value("${horecaos.audit.archive.call-attempt-timeout:10s}") Duration attemptTimeout) {

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        secrets.resolve(require(accessKeyReference)).reveal(),
                        secrets.resolve(require(secretKeyReference)).reveal())))
                // A whole year's worth of evidence can be a real upload, and this
                // runs off the request thread on a schedule, so it is given more
                // room than media's interactive 15s/5s — see
                // ObjectStorageConfiguration for why a timeout is stated here
                // rather than inherited: the SDK default is a socket timeout
                // times up to three retries, a worst case nothing here plans for.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(callTimeout)
                        .apiCallAttemptTimeout(attemptTimeout)
                        .build())
                // Same reasoning as media's client: an implementation of the S3
                // API that is not S3 itself may not accept the SDK's default
                // flexible-checksum headers on every call.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        return new S3AuditArchiveStore(client, bucket);
    }

    /** Mirrors {@code ObjectStorageConfiguration.require}, checked at startup rather than at first archive. */
    static SecretReference require(String reference) {
        SecretReference parsed = SecretReference.parse(reference);
        if (parsed.category() != SecretCategory.OBJECT_STORAGE) {
            throw new IllegalStateException(
                    "Audit archive credentials must use the object_storage category, got " + parsed.category());
        }
        return parsed;
    }
}
