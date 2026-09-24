package uz.horecaos.platform.integration.api.provider;

import java.util.Arrays;
import java.util.Optional;

/**
 * The pairings the tenant-facing mapping pane offers (ADR 0012/0026, gap-map
 * rows 10.8b and 1.2i): products, payment types, discounts, couriers,
 * cancellation reasons, a channel's POS category/order code, and — since
 * gap-map row 1.2i's fix path — a line's own variant and a line's modifier.
 *
 * <p>{@code integration.provider_entity_mappings.entity_type} is a free
 * {@code varchar(64)} with no {@code CHECK} — three code paths already write
 * different vocabularies into it ({@code VARIANT_PARENT}/{@code VARIANT} from
 * the catalog sync engine, {@code MENU_ITEM} from the partner/aggregator
 * module) and this enum adds a fourth rather than colliding with any of them.
 * {@link #storedAs()} is what actually goes in the column; {@link #PRODUCT}
 * intentionally stores {@code VARIANT_PARENT} so a mapping made through this
 * API is the same row {@link uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosTargetCatalog}
 * and the live-availability feed already read — a merchant pairing a product
 * by hand here changes what the sync engine sees next run, rather than
 * writing to a fourth table nothing else looks at.
 *
 * <p>{@link #VARIANT} and {@link #MODIFIER} store {@code VARIANT} and {@code
 * MODIFIER} — the exact literals {@code PosOrderExportService} already
 * queries for a confirmed order's own line and modifier mappings, and {@code
 * VARIANT} is also the literal the catalog sync engine's own apply step
 * already writes (gap-map row 10.8b's {@code DifferenceEngine.EntityType}).
 * A mapping made through either of these two by hand is therefore read by the
 * exact same lookup a POS export's {@code LINE_UNMAPPED}/{@code
 * MODIFIER_UNMAPPED} refusal just failed — the fix path this pair exists for.
 * Unlike {@link #PRODUCT}, no adapter in this build discovers a per-variant or
 * per-modifier candidate list, so their external side is always {@code
 * sourced=false}: an operator types the provider's own code by hand, the same
 * fallback {@link #COURIER} and {@link #CANCELLATION_REASON} already use.
 */
public enum MappingEntityType {
    PRODUCT("VARIANT_PARENT"),
    PAYMENT_TYPE("PAYMENT_TYPE"),
    DISCOUNT("DISCOUNT"),
    COURIER("COURIER"),
    CANCELLATION_REASON("CANCELLATION_REASON"),
    CHANNEL_POS_CODE("CHANNEL_POS_CODE"),
    VARIANT("VARIANT"),
    MODIFIER("MODIFIER");

    private final String storedAs;

    MappingEntityType(String storedAs) {
        this.storedAs = storedAs;
    }

    /** The literal value written to and read from {@code entity_type}. */
    public String storedAs() {
        return storedAs;
    }

    public static Optional<MappingEntityType> forStoredValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.storedAs.equals(value))
                .findFirst();
    }
}
