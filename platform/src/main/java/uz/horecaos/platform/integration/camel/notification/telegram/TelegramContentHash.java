package uz.horecaos.platform.integration.camel.notification.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A local stand-in for {@code notifications.domain.ContentHashes}, which this
 * package must not import: a Camel adapter reaching into another module's
 * domain package is exactly the boundary {@code ModularArchitectureTests}
 * exists to keep closed, even for something this small. Same algorithm, no
 * shared type.
 */
final class TelegramContentHash {

    private TelegramContentHash() {}

    static String of(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
