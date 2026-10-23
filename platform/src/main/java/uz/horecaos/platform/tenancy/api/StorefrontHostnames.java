package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The address a brand's storefront answers on (ADR 0183 D27, ADR 0036 row 10.5): the single <em>verified</em> hostname of
 * the active WEB channel that sells at the brand's branches.
 *
 * <p>A channel is a tenant-level object and does not belong to a brand (V0020), so the brand is found through the
 * branches a channel sells at ({@code tenant.sales_channel_locations}). Only a verified hostname resolves: an unverified
 * custom domain is one the platform has not confirmed the tenant controls, and a link built on it would send a customer
 * to somebody else's page. When two verified hostnames qualify there is no telling which is "the" storefront, so the
 * answer is {@link Reason#AMBIGUOUS} and not a guess, the same "exactly one answers" rule the QR table card applies.
 *
 * <p>Read on demand and never copied, so a hostname that is released or un-verified stops being linkable on the next read.
 */
public interface StorefrontHostnames {

    Resolution forBrand(UUID tenantId, UUID brandId);

    /** Why a brand has no storefront address. */
    enum Reason {
        /** No active WEB channel selling at the brand's branches has claimed a hostname. */
        NOT_CLAIMED,
        /** A hostname is claimed and none of them has been verified yet. */
        NOT_VERIFIED,
        /** More than one verified hostname qualifies. */
        AMBIGUOUS
    }

    /**
     * @param hostname lower case, no scheme; set exactly when {@code reason} is null
     */
    record Resolution(@Nullable String hostname, @Nullable Reason reason) {

        public Resolution {
            if ((hostname == null) == (reason == null)) {
                throw new IllegalArgumentException("A resolution has a hostname or a reason, and never both");
            }
        }

        public static Resolution of(String hostname) {
            return new Resolution(Objects.requireNonNull(hostname), null);
        }

        public static Resolution missing(Reason reason) {
            return new Resolution(null, Objects.requireNonNull(reason));
        }

        public boolean resolved() {
            return hostname != null;
        }

        /** {@code https://<hostname>} with no trailing slash; only when {@link #resolved()}. */
        public String origin() {
            return "https://" + Objects.requireNonNull(hostname, "No hostname resolved");
        }
    }
}
