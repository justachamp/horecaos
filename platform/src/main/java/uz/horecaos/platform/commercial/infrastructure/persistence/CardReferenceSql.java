package uz.horecaos.platform.commercial.infrastructure.persistence;

/**
 * The one SQL reading of {@code CardTokenReferences}' two shapes, for the stores that must ask "whose
 * account was this attempt made through" (ADR 0095, V0505).
 *
 * <p>A reference the tenant bound itself is {@code <installation id>:<provider token>}. Anything else is
 * one a staff member typed, and it is handed to whichever account is active, so it belongs to the
 * installation that is active when somebody asks. A row with no reference at all (a statement charge for a
 * CARD tenant with no token yet) is in the same position.
 */
final class CardReferenceSql {

    private static final String COMPOSED =
            "'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}:.'";

    /**
     * A predicate over a table's {@code card_token_reference} that is true when the attempt was asked through
     * the installation bound to {@code :installationId} (a text parameter), or through the active one.
     */
    static final String ASKED_THROUGH_INSTALLATION = "(card_token_reference IS NULL"
            + " OR card_token_reference LIKE (:installationId || ':%')"
            + " OR card_token_reference !~ " + COMPOSED + ")";

    private CardReferenceSql() {}
}
