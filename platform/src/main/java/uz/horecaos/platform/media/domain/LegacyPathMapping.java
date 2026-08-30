package uz.horecaos.platform.media.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * An approved legacy directory to owner mapping (ADR 0010, phase 3).
 *
 * <p>Approved rather than inferred. Guessing that {@code /uploads/brand12/} is
 * brand twelve is how one restaurant's photographs end up on another's menu, and
 * a media asset attached to the wrong tenant is a data-protection incident
 * rather than a display bug.
 */
public record LegacyPathMapping(UUID tenantId, String prefix, MediaOwner owner) {

    public LegacyPathMapping {
        // Held in the same shape as a normalized path, so that a mapping written
        // as "/uploads/pizza/" and a path normalized to "uploads/pizza/x.jpg"
        // compare. Leaving the two shapes to meet at the comparison is how a
        // mapping silently matches nothing.
        prefix = prefix.strip().replace('\\', '/').replaceAll("^/+|/+$", "");
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("A legacy path mapping needs a prefix");
        }
    }

    /**
     * The owner of a path, by longest matching prefix.
     *
     * <p>Longest wins so that a specific mapping can carve an exception out of a
     * general one — {@code /uploads/} to a tenant, {@code /uploads/pizza/} to one
     * of its brands — without the general mapping having to be split.
     *
     * @return empty when nothing claims this path, which is an item for a human
     *         to map rather than a file to skip quietly
     */
    public static Optional<LegacyPathMapping> resolve(LegacyPath path,
            Collection<LegacyPathMapping> mappings) {
        return mappings.stream()
                .filter(mapping -> matches(mapping, path))
                .max(Comparator.comparingInt(mapping -> mapping.prefix().length()));
    }

    private static boolean matches(LegacyPathMapping mapping, LegacyPath path) {
        // Compared with the separator attached, so uploads/pizza does not claim
        // uploads/pizzahut — a prefix match on raw strings is one character away
        // from handing a directory to the wrong owner.
        return path.normalized().startsWith(mapping.prefix() + "/");
    }
}
