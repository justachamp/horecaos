package uz.horecaos.platform.migration.application.importing;

/**
 * The mapping changed and the version did not (ADR 0024).
 *
 * <p>The refusal ADR 0024 asks for, at the only moment it can still be free.
 * Once a page has been written under a mapping the registry does not know about,
 * the entity type contains two semantics and the only way back is a remediation
 * over the rows the old version wrote — findable, because {@code
 * migration.entity_mappings} stamps the version on every row, but a run rather
 * than an edit.
 *
 * <p>The fix is a decision, not a retry: either restore the transformation to
 * what version <em>n</em> declared, or declare version <em>n+1</em> and start a
 * {@code REMEDIATION} run over what version <em>n</em> produced. Nothing here
 * chooses between those, because one of them silently rewrites approved history.
 */
public class RemediationRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String entityType;
    private final int declaredVersion;

    public RemediationRequiredException(String entityType, int declaredVersion,
            String registeredDigest, String actualDigest) {
        super(("The %s transformation still calls itself version %d, but its declared rules now "
                + "hash to %s where version %d was registered as %s. A changed mapping is a new "
                + "version and a remediation run over the rows the old one wrote (ADR 0024); it is "
                + "not a quiet re-import under the same number.")
                .formatted(entityType, declaredVersion, abbreviate(actualDigest),
                        declaredVersion, abbreviate(registeredDigest)));
        this.entityType = entityType;
        this.declaredVersion = declaredVersion;
    }

    public RemediationRequiredException(String entityType, int declaredVersion) {
        super(("The %s transformation declares version %d and no such version is registered for "
                + "this program. Declare the mapping before running it: a crosswalk row stamped "
                + "with a version nothing defines cannot be remediated, because nobody can say "
                + "what it was written under.")
                .formatted(entityType, declaredVersion));
        this.entityType = entityType;
        this.declaredVersion = declaredVersion;
    }

    public String entityType() {
        return entityType;
    }

    public int declaredVersion() {
        return declaredVersion;
    }

    /** Enough digest to identify it in a message, without a 64-character wall. */
    private static String abbreviate(String digest) {
        return digest == null || digest.length() < 12 ? String.valueOf(digest) : digest.substring(0, 12);
    }
}
