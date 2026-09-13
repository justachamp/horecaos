package uz.horecaos.platform.integration.api.provider;

import java.util.Arrays;
import java.util.Optional;

/**
 * The six pairings the tenant-facing mapping pane offers (ADR 0012/0026,
 * gap-map row 10.8b): products, payment types, discounts, couriers,
 * cancellation reasons, and a channel's POS category/order code.
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
 */
public enum MappingEntityType {
    PRODUCT("VARIANT_PARENT"),
    PAYMENT_TYPE("PAYMENT_TYPE"),
    DISCOUNT("DISCOUNT"),
    COURIER("COURIER"),
    CANCELLATION_REASON("CANCELLATION_REASON"),
    CHANNEL_POS_CODE("CHANNEL_POS_CODE");

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
