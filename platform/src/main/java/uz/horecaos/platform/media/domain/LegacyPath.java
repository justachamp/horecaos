package uz.horecaos.platform.media.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;

/**
 * A path from the legacy filesystem, normalized and judged (ADR 0010).
 *
 * <p>The migration reads paths that came out of legacy business rows, and those
 * rows were written by an application that concatenated user input. A path is
 * therefore attacker-influenced data, and the copy step turns a path into a
 * filesystem read: {@code ../../etc/shadow} in a business row becomes a file in
 * an object store that the platform then serves.
 *
 * <p>Normalization happens here, once, and the normalized form is what the
 * migration stores and keys on. Two spellings of the same file — a doubled
 * separator, a trailing dot segment — would otherwise be copied twice and become
 * two assets, which makes the reconciliation counts disagree for a reason nobody
 * can find.
 */
public record LegacyPath(String normalized) {

    /**
     * Only the extensions the upload path already accepts.
     *
     * <p>Not proof of anything — the content probe is what decides what a file
     * is — but a legacy tree contains scripts, backups and dumps alongside the
     * photographs, and there is no reason for the migration to read them at all.
     */
    private static final java.util.Set<String> MIGRATABLE_EXTENSIONS =
            java.util.Set.of("jpg", "jpeg", "png", "webp", "avif");

    /**
     * Normalizes a legacy path, or refuses it.
     *
     * @return empty when the path is unsafe or unusable: it escapes its root,
     *         carries control characters, is empty, or names something the
     *         migration has no business reading
     */
    public static Optional<LegacyPath> of(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String candidate = raw.strip().replace('\\', '/');
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            // A NUL truncates the path at the system call while the row keeps
            // the whole string, so what was checked and what is opened differ.
            if (character < 0x20 || character == 0x7F) {
                return Optional.empty();
            }
        }

        Deque<String> segments = new ArrayDeque<>();
        for (String segment : candidate.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                // Resolved rather than merely rejected on sight, because
                // "a/b/../c" is an ordinary path; only an ascent that leaves the
                // root is an escape, and that is what the empty deque means.
                if (segments.isEmpty()) {
                    return Optional.empty();
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        String normalized = String.join("/", segments);
        if (!hasMigratableExtension(normalized)) {
            return Optional.empty();
        }
        return Optional.of(new LegacyPath(normalized));
    }

    /** The final segment, for a display label only; it never reaches an object key. */
    public String filename() {
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static boolean hasMigratableExtension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return false;
        }
        return MIGRATABLE_EXTENSIONS.contains(
                path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
