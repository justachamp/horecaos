package uz.horecaos.platform.tenancy.api.geo;

import java.util.Objects;
import java.util.UUID;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The region a lookup is made in (ADR 0037, ADR 0145 decision 7): an identity, a centre to
 * bias toward and the box that bounds the answer.
 *
 * <p>The id is carried, not just the code: a tenant's region and a platform region may share
 * a code, and a cache keyed by code would hand one tenant the other's answer.
 */
public record GeoRegion(UUID id, String code, GeoPoint centre, GeoBoundingBox box) {

    public GeoRegion {
        Objects.requireNonNull(id, "A region id is required");
        Objects.requireNonNull(code, "A region code is required");
        Objects.requireNonNull(centre, "A region centre is required");
        Objects.requireNonNull(box, "A region box is required");
    }
}
