package uz.horecaos.platform.iam.application.terminals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The key material a PIN and an activation code are keyed under (ADR 0155, ADR 0028), by version.
 *
 * <p>Two versions can be resolvable at once: the <em>current</em> one every new digest is made under, and optionally
 * the <em>previous</em> one, so a PIN keyed under it still verifies and is re-keyed under the current one on its next
 * successful unlock. The pepper never reaches a database, a log or a response; this class holds bytes in memory and
 * exposes no way to read them back except the keyed hash inputs {@link PinDigests} uses.
 */
public final class TerminalPinPepper {

    private static final int MINIMUM_BYTES = 16;

    private final int currentVersion;
    private final Map<Integer, byte[]> byVersion;

    private TerminalPinPepper(int currentVersion, Map<Integer, byte[]> byVersion) {
        this.currentVersion = currentVersion;
        this.byVersion = byVersion;
    }

    public static TerminalPinPepper of(
            int currentVersion, byte[] current, @Nullable Integer previousVersion, byte @Nullable [] previous) {
        Objects.requireNonNull(current, "A terminal PIN pepper is required");
        require(current);
        if (currentVersion < 1 || currentVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("The pepper version is a small positive number");
        }
        Map<Integer, byte[]> keys = new LinkedHashMap<>();
        keys.put(currentVersion, current.clone());
        if (previous != null && previousVersion != null) {
            require(previous);
            if (previousVersion.equals(currentVersion)) {
                throw new IllegalArgumentException("The previous pepper must carry a version of its own");
            }
            keys.put(previousVersion, previous.clone());
        }
        return new TerminalPinPepper(currentVersion, Map.copyOf(keys));
    }

    private static void require(byte[] key) {
        if (key.length < MINIMUM_BYTES) {
            throw new IllegalArgumentException("A terminal PIN pepper shorter than 16 bytes is not worth having");
        }
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** The key for a stored digest's version, or {@code null} when that version is no longer resolvable. */
    byte @Nullable [] key(int version) {
        return byVersion.get(version);
    }

    byte[] currentKey() {
        return Objects.requireNonNull(byVersion.get(currentVersion));
    }
}
