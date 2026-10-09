# ИКПУ/MXIK importer fixtures (ADR 0173)

`tasnif-fixture.csv` is **invented**. It has the shape the mapping profile in `MxikMappingProfile` reads and
nothing more: the official State Tax Committee file has not been obtained, so its real header row, whether it
carries both languages, and whether package and unit lists are columns are open inputs (ADR 0173).

The codes below are not real classification codes; do not copy them into a tenant's catalog.

Where the real file goes: upload it through `POST /api/v1/control-plane/fiscal-reference/mxik/imports`
(runbook `docs/runbooks/mxik-reference-import.md`), then change `MxikMappingProfile`'s aliases to its headers and
add a trimmed copy (a few hundred rows, no licence-bearing text) beside this one.
