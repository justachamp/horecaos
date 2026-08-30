# Plan: <short title>

- **Spec:** [`spec.md`](spec.md)
- **Author:** <engineer>
- **Date:** <YYYY-MM-DD>

Written in plan mode against the real codebase. The test of a good plan: someone who was
not in the session could implement it from this file alone.

## Files that change

| File | Change |
|---|---|

## Order of work

Numbered steps, each ending somewhere `make verify` passes. Note which steps are
independent — those are the ones that can run as parallel sessions in separate worktrees.

1. …

## Risks

What could break, and what makes it safe. The riskiest step, named explicitly.

| Risk | Mitigation |
|---|---|

## Proof

The exact commands and the tests that must exist and pass. `make verify` is the floor,
not the whole answer — name the tests that prove *this* change.

```bash
make verify
```

- [ ] New tests, named:
- [ ] Tenant isolation negative case (cross-tenant access denied)
- [ ] Migration applies on a populated database and is idempotent
- [ ] Rollback rehearsed

## Divergence log

Implementation reveals things planning cannot. Record what changed and why — this is what
makes the plan trustworthy next time, rather than fiction.

| Date | Departed from plan | Why |
|---|---|---|
