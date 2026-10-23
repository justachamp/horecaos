package uz.horecaos.platform.support;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import uz.horecaos.platform.media.api.ObjectStorage;

/**
 * An {@link ObjectStorage} that keeps objects in a map, for suites whose subject is not the store. A suite that is about
 * what the store does (a presigned link that actually serves the bytes) uses {@link ObjectStoreContainer} instead.
 *
 * <p>{@link #presignDownload} answers a URL that names the bucket, the key and the validity, so a test can see what a link
 * was asked for without a network.
 */
public final class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

    @Override
    public PresignedUpload presignUpload(
            String bucket, String key, String contentType, long maxSizeBytes, Duration validFor) {
        throw new UnsupportedOperationException("uploads are not part of what this double serves");
    }

    @Override
    public URI presignDownload(String bucket, String key, Duration validFor) {
        return URI.create("https://objects.test/" + bucket + "/" + key + "?validFor=" + validFor.toSeconds());
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        byte[] content = objects.get(id(bucket, key));
        return content == null
                ? Optional.empty()
                : Optional.of(new StoredObject(
                        content.length, contentTypes.getOrDefault(id(bucket, key), ""), Optional.empty(), "etag"));
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        objects.put(id(bucket, key), content.clone());
        contentTypes.put(id(bucket, key), contentType);
    }

    @Override
    public void delete(String bucket, String key) {
        objects.remove(id(bucket, key));
        contentTypes.remove(id(bucket, key));
    }

    /** The bytes stored under a key, or null when there is nothing there. */
    public byte @org.jspecify.annotations.Nullable [] content(String bucket, String key) {
        byte[] content = objects.get(id(bucket, key));
        return content == null ? null : content.clone();
    }

    public boolean exists(String bucket, String key) {
        return objects.containsKey(id(bucket, key));
    }

    public int count() {
        return objects.size();
    }

    private static String id(String bucket, String key) {
        return bucket + "/" + key;
    }
}
