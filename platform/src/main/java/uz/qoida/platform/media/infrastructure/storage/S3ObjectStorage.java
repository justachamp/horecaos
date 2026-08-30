package uz.qoida.platform.media.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import uz.qoida.platform.media.api.ObjectStorage;

/**
 * S3-compatible storage (ADR 0010), used against MinIO locally and a hosted
 * S3 API in production.
 *
 * <p>No public-URL convention is coded here on purpose. Providers differ on
 * path versus virtual-host style, and a hard-coded pattern is what makes the
 * ADR 0034 move from a local provider to AWS expensive.
 */
@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorage(S3Client client, S3Presigner presigner) {
        this.client = client;
        this.presigner = presigner;
    }

    @Override
    public PresignedUpload presignUpload(String bucket, String key, String contentType,
            long maxSizeBytes, Duration validFor) {

        // Content type and length are part of the signed request, so the client
        // cannot upload a different type or an unbounded body under this URL.
        // Without them a presigned PUT is a write-anything-here token.
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(maxSizeBytes)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .putObjectRequest(put)
                .build());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);

        return new PresignedUpload(URI.create(presigned.url().toString()), headers,
                Instant.now().plus(validFor));
    }

    @Override
    public URI presignDownload(String bucket, String key, Duration validFor) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(validFor)
                        .getObjectRequest(get)
                        .build())
                .url().toString());
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        try {
            HeadObjectResponse response = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(
                    response.contentLength(),
                    response.contentType(),
                    Optional.ofNullable(response.checksumSHA256()),
                    response.eTag()));
        } catch (NoSuchKeyException absent) {
            // A missing object is an ordinary outcome here — the client was
            // given a URL and never used it — not an error worth an exception.
            return Optional.empty();
        }
    }

    @Override
    public byte[] readPrefix(String bucket, String key, int maxBytes) {
        try {
            // A Range header, so the store sends only the prefix. Reading the
            // whole object and truncating locally would still transfer every
            // byte an uploader chose to send.
            return client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .range("bytes=0-" + (maxBytes - 1))
                            .build())
                    .asByteArray();
        } catch (NoSuchKeyException absent) {
            return new byte[0];
        }
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        client.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
