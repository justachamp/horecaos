package uz.horecaos.platform.ordering.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The ADR 0030 keys of marked-goods capture (ADR 0161).
 *
 * <p>The codes live in the {@code fiscal.marking.} namespace -- the policy is about a fiscal
 * obligation -- but the keys are declared here and not in {@code fiscal.api}: the server-side check is
 * made inside {@code ordering}, which cannot import {@code fiscal} ({@code fiscal} imports {@code
 * ordering.api}, and a reference the other way would close a cycle).
 *
 * <p>Neither key is tenant-visible. They are platform decisions about the shape the state system's
 * code takes and about what a person may type, not a restaurant's preference, and a wrong assumption
 * about the code is meant to be corrected once, by the platform, for everyone.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in {@code
 * tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is not
 * possible and importing this from there would make the two modules cyclic. The registry therefore
 * carries identical declarations, and {@code MarkingConfigurationKeyTests} fails the build if the two
 * ever drift apart. The flag {@code feature.marked_goods} (ADR 0082) is not here: catalog and ordering
 * both read it, so it is exposed once through {@code tenancy.api.FeatureFlagKeys}.
 */
public final class MarkingConfigurationKeys {

    /** The code both declarations share. */
    public static final String STRUCTURE_CHECK_CODE = "fiscal.marking.structure_check";

    /** The code both declarations share. */
    public static final String ALLOW_KEYED_ENTRY_CODE = "fiscal.marking.allow_keyed_entry";

    /**
     * {@code GS1} (the default) checks the assumed GS1 element-string shape of a code; {@code NONE}
     * checks only the character set and length. The repository holds no specification of the Asl Belgisi
     * code, so this is the switch that makes a wrong assumption a configuration change.
     */
    public static final ConfigurationKey<String> STRUCTURE_CHECK = ConfigurationKey.of(
                    STRUCTURE_CHECK_CODE, String.class)
            .defaultValue("GS1")
            .ownedBy("fiscal")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("How much of a marking code's shape the server checks: GS1 (the assumed GS1 "
                    + "element-string shape) or NONE (only the character set and length).")
            .build();

    /**
     * Whether a hand-typed or pasted code may be captured. Off: the human-readable text on a package does
     * not include the cryptographic tail, so a keyed code is a guess at best.
     */
    public static final ConfigurationKey<Boolean> ALLOW_KEYED_ENTRY = ConfigurationKey.of(
                    ALLOW_KEYED_ENTRY_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("fiscal")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Whether a marking code typed or pasted by hand is accepted at the pass. Off by "
                    + "default: a keyed code is not the code on the package.")
            .build();

    private MarkingConfigurationKeys() {}
}
