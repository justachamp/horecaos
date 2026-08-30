/**
 * Paged extraction, versioned transformation, and the import ports that write
 * through the target's own domain services (ADR 0024).
 *
 * <p>Named {@code importing} and not {@code import}, which is what ADR 0024's
 * shape asks for and what Java forbids: {@code import} is a keyword, so it cannot
 * be a package name segment. The directory follows the package.
 *
 * <p>The shape of every wave is the same seven steps, and this package covers
 * three of them — extract, transform, upsert — with the control plane already
 * carrying the rest.
 *
 * <ol>
 * <li>Extraction is keyset paging over a stable key, never an offset. The legacy
 * database is live while this reads it, and an offset silently skips rows when
 * the table shifts underneath the reader. The row skipped would be a legacy
 * record nobody then accounts for.</li>
 * <li>Transformation is versioned, and the version is a digest over the rules
 * rather than a number somebody increments. A migrator whose digest disagrees
 * with the registered current version refuses to start, so a changed mapping
 * produces an explicit remediation run rather than two semantics in one table.</li>
 * <li>The import writes through {@link uz.horecaos.platform.migration.application.importing.ImportPort},
 * whose whole contract is that it calls a target domain service. ADR 0024
 * rejected change capture writing directly into target tables because it bypasses
 * every invariant, validation and audit path the last twenty ADRs exist to
 * enforce, and an import port that reached for {@code JdbcClient} would be that
 * alternative under a different name.</li>
 * </ol>
 *
 * <p>What makes the domain-service route survivable is
 * {@link uz.horecaos.platform.migration.api.ImportContext}: the same call that
 * confirms an order today would send a confirmation, capture a payment and book
 * a courier, and every adapter that does one of those consults the flag. The
 * import gets the validation and the audit and none of the effects.
 *
 * <p>Checkpointing is the other invariant. A page's target writes, its crosswalk
 * rows, its quarantine items and its cursor advance are one transaction. The
 * control plane and the target are two schemas of one PostgreSQL, so ADR 0024's
 * "checkpoint only after a target commit" is available in its strongest form —
 * the same commit — and there is no window in which a page is imported and
 * unrecorded, or recorded and not imported.
 */
package uz.horecaos.platform.migration.application.importing;
