/**
 * The migration control plane's services: programs and the scopes under them,
 * the transition engine that moves a scope, the ownership gate the rest of the
 * platform is stopped by, restartable runs, and quarantine (ADR 0024).
 *
 * <p>The rules that ADR 0024 states in prose and the domain deliberately cannot
 * enforce live here. {@code ScopeStateMachine} answers whether a move exists;
 * whether it is allowed <em>now</em> — reconciliation cleared, every in-scope
 * source decided, an approved cutover decision recorded before the modes change,
 * a paused scope returning to the state it actually left — is a runtime question
 * about stored evidence, and {@link
 * uz.qoida.platform.migration.application.MigrationScopeService} is where the
 * refusals are. The ADR's requirement that no platform-admin UI may skip them is
 * kept by there being no other way to move a scope.
 *
 * <p>Persistence is reached through the ports declared in this package rather
 * than through the JDBC stores directly, matching {@code OrderingTenantContext}.
 * The port doubles as the specification the store is written against: each
 * method's contract says which index it is expected to probe and which
 * uniqueness the schema already guarantees, so a store cannot satisfy the
 * signature while quietly dropping a tenant predicate.
 */
package uz.qoida.platform.migration.application;
