package uz.horecaos.platform.migration.application;

/**
 * The program, scope, run or quarantine item does not exist for this tenant.
 *
 * <p>"Does not exist" and "belongs to another tenant" are the same failure on
 * purpose. Every lookup in this package carries its tenant predicate, so a
 * caller probing identifiers learns only that the one they tried is not theirs.
 */
public class MigrationResourceNotFoundException extends RuntimeException {

    public MigrationResourceNotFoundException(String message) {
        super(message);
    }
}
