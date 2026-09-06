-- ADR 0008: a FAILED onboarding run must not block a new one.
--
-- V0014's uq_onboarding_run_active excluded only ACTIVE and CANCELLED from
-- "one active run per tenant", so a run that failed permanently still held
-- the index entry forever. A tenant stuck behind a step nobody could fix had
-- exactly two doors: resume (which only ever reopens FAILED steps on the
-- same run, and does nothing when the fix requires a different template or
-- a different owner) and nothing else, because OnboardingService.startRun
-- does a plain INSERT with no existing-run guard of its own — the partial
-- index is the only thing enforcing the invariant, and it was enforcing the
-- wrong one. PostgreSQL cannot ALTER a partial index's predicate in place,
-- so this drops and recreates it exactly as V0172 (op cit., a CHECK
-- constraint) had to restate a value list in full rather than extend it.
--
-- FAILED joins ACTIVE and CANCELLED as "not active": a run that finished,
-- however it finished, no longer contends with a new one for the same
-- tenant. A genuinely in-flight run (DRAFT, PROVISIONING, CONFIGURING,
-- VALIDATING, READY, ACTIVATING) is unaffected and still blocks a second.
DROP INDEX tenant.uq_onboarding_run_active;

CREATE UNIQUE INDEX uq_onboarding_run_active
    ON tenant.onboarding_runs (tenant_id)
    WHERE status NOT IN ('ACTIVE', 'CANCELLED', 'FAILED');
