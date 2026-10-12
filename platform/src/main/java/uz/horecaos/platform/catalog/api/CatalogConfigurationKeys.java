package uz.horecaos.platform.catalog.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The catalog module's ADR 0030 configuration keys.
 *
 * <p>{@link #AUTO_ADD_MANDATORY_ALLOWED} is ADR 0158's legal switch: a line the customer cannot remove
 * is a charge the customer did not choose, and the wording that makes it lawful is a question for legal.
 * It is declared without {@code tenantVisible()}, so neither the tenant configuration surface lists nor
 * writes it, and it is written only through the platform control-plane configuration controller under
 * {@code PLATFORM_ADMIN}, on the owner's instruction.
 *
 * <p>The three {@code mxik} keys are ADR 0173's: how stale the national classification list may be
 * before an alert is raised, how large a withdrawal a full file may cause before a person must look, and
 * the largest file the importer accepts in a request body. All three are platform-wide: the list is one
 * national dataset, not a tenant's.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in {@code
 * tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is not
 * possible and importing this from there would make the two modules cyclic. The registry therefore
 * carries identical declarations, and {@code CatalogConfigurationKeyTests} fails the build if the two
 * ever drift apart.
 */
public final class CatalogConfigurationKeys {

    private CatalogConfigurationKeys() {}

    /** The code both declarations share. */
    public static final String AUTO_ADD_MANDATORY_ALLOWED_CODE = "catalog.auto_add_mandatory_allowed";

    /**
     * Whether a mandatory auto-add rule may be published. Off at launch: a tenant that wants unremovable
     * packaging waits for the owner's switch, not for engineering.
     */
    public static final ConfigurationKey<Boolean> AUTO_ADD_MANDATORY_ALLOWED = ConfigurationKey.of(
                    AUTO_ADD_MANDATORY_ALLOWED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("catalog")
            .settableAt(ScopeType.TENANT)
            .describedAs("Whether a mandatory auto-add rule (a cart line the customer cannot remove) may be "
                    + "published for this tenant. Written by a platform administrator on the owner's instruction.")
            .build();

    /** The code both declarations share. */
    public static final String MXIK_REFERENCE_MAX_AGE_DAYS_CODE = "catalog.mxik_reference_max_age_days";

    /** Days the loaded ИКПУ/MXIK list may age before a platform alert is raised (ADR 0173). */
    public static final ConfigurationKey<Integer> MXIK_REFERENCE_MAX_AGE_DAYS = ConfigurationKey.of(
                    MXIK_REFERENCE_MAX_AGE_DAYS_CODE, Integer.class)
            .defaultValue(45)
            .ownedBy("catalog")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Days the loaded national ИКПУ/MXIK classification list may age before a platform "
                    + "alert is raised.")
            .build();

    /** The code both declarations share. */
    public static final String MXIK_IMPORT_WITHDRAWAL_THRESHOLD_PERCENT_CODE =
            "catalog.mxik_import_withdrawal_threshold_percent";

    /** Percent of the currently valid rows a full-snapshot import may withdraw before it is held for review. */
    public static final ConfigurationKey<Integer> MXIK_IMPORT_WITHDRAWAL_THRESHOLD_PERCENT = ConfigurationKey.of(
                    MXIK_IMPORT_WITHDRAWAL_THRESHOLD_PERCENT_CODE, Integer.class)
            .defaultValue(2)
            .ownedBy("catalog")
            .settableAt(ScopeType.PLATFORM)
            .describedAs(
                    "Percent of the currently valid ИКПУ/MXIK rows a full-snapshot import may withdraw "
                            + "before it is held for review. A truncated or wrong file otherwise empties the list in one request.")
            .build();

    /** The code both declarations share. */
    public static final String MXIK_IMPORT_MAX_BYTES_CODE = "catalog.mxik_import_max_bytes";

    /**
     * The largest decoded file the importer accepts in a request body. 32 MiB is a placeholder the first real
     * file replaces (ADR 0173).
     */
    public static final ConfigurationKey<Long> MXIK_IMPORT_MAX_BYTES = ConfigurationKey.of(
                    MXIK_IMPORT_MAX_BYTES_CODE, Long.class)
            .defaultValue(33_554_432L)
            .ownedBy("catalog")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("The largest decoded ИКПУ/MXIK file, in bytes, the importer accepts in one request.")
            .build();
}
