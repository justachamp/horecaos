/**
 * What the migration control plane exposes to the rest of the platform: who owns
 * writes for a capability, the flag a historical import runs under, and the
 * contract an import port implements (ADR 0024).
 *
 * <p>Deliberately small. No program, scope, run, mapping, quarantine, or cutover
 * type appears here — those are the control plane's own bookkeeping, and a
 * module that could read them would start making its own ownership decisions
 * from them. The one question another module may ask is whether it is the writer
 * right now, and {@link uz.qoida.platform.migration.api.MigrationOwnershipPort}
 * is the only way to ask it.
 *
 * <p>Three groups of type live here, and each is here because another module has
 * to reach it.
 *
 * <p>{@link uz.qoida.platform.migration.api.ImportContext},
 * {@link uz.qoida.platform.migration.api.ExternalEffect} and
 * {@link uz.qoida.platform.migration.api.ImportSuppression} are read by every
 * adapter with an external effect — the outbox listeners, notification delivery,
 * payment intents, courier booking, POS export, benefit metering, inventory
 * movements. They are consulted at the boundary where the platform reaches
 * outside itself and never by validation, an invariant, or the audit recorder.
 *
 * <p>{@link uz.qoida.platform.migration.api.ImportPort} and the types it needs —
 * {@link uz.qoida.platform.migration.api.ExtractionSpec},
 * {@link uz.qoida.platform.migration.api.LegacyRecord},
 * {@link uz.qoida.platform.migration.api.Transformation},
 * {@link uz.qoida.platform.migration.api.TransformationOutcome} — are
 * <strong>implemented</strong> by other modules rather than called by them, and
 * that inversion is the point. ADR 0024 requires an import to write through the
 * target's own domain services, and Spring Modulith refuses a dependency from
 * {@code migration} onto another module's {@code application} package. A port
 * written on the migration side therefore could not call the service; it would
 * have to reach for {@code JdbcClient}, which is precisely the ad hoc target SQL
 * the ADR rejected. Exposing the contract here and letting each module implement
 * it for its own aggregates makes the rule structural instead of remembered.
 *
 * <p>{@link uz.qoida.platform.migration.api.MigrationCapability} lives here and
 * not in {@code migration.domain} with the rest of the vocabulary, because it is
 * the one word a caller has to say. Asking the port a question means naming the
 * capability being asked about, so a capability enum the caller could not
 * reference would make the port uncallable from outside this module — and the
 * alternative, a second enum with the same constants restated per caller, drifts
 * on the first capability anyone adds. The ownership guarantee is only provable
 * from the scope table if the caller and the table name the same capabilities.
 *
 * <p>The rest of the vocabulary — scope state, write and read mode, run type,
 * mapping and reconciliation status — deliberately stays internal. A caller reads
 * {@link uz.qoida.platform.migration.api.CapabilityOwnership} through its
 * predicates rather than by switching on a state, which is what keeps "may I
 * write" a question this module answers instead of one every caller re-derives.
 */
@org.springframework.modulith.NamedInterface("api")
package uz.qoida.platform.migration.api;
