#!/usr/bin/env python3
"""Deterministic repository rules that need no JVM.

Fast enough to run in every agent session and in CI. Each check maps to a rule
in AGENTS.md; the docstring on each function names it. Exit 0 = clean.

Run directly, or via `make lint`.
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIG = ROOT / "src/main/resources/db/migration"
MAIN = ROOT / "src/main/java"
ADR = ROOT / "docs/adr"

GREEN, RED, DIM, OFF = "\033[32m", "\033[31m", "\033[2m", "\033[0m"

failures: list[str] = []


def result(name: str, problems: list[str], hint: str = "") -> None:
    if problems:
        failures.append(name)
        print(f"{RED}FAIL{OFF} {name}")
        for p in problems[:25]:
            print(f"       {p}")
        if len(problems) > 25:
            print(f"       {DIM}... and {len(problems) - 25} more{OFF}")
        if hint:
            print(f"       {DIM}{hint}{OFF}")
    else:
        print(f"{GREEN}ok{OFF}   {name}")


def migrations() -> list[tuple[int, Path]]:
    out = []
    for p in sorted(MIG.glob("V*__*.sql")):
        m = re.match(r"V(\d+)__", p.name)
        if m:
            out.append((int(m.group(1)), p))
    return sorted(out)


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT))


def java_files() -> list[Path]:
    return list(MAIN.rglob("*.java"))


# --- checks ---------------------------------------------------------------


def check_unique_versions() -> None:
    """Flyway: a duplicate version silently shadows a migration."""
    seen: dict[int, str] = {}
    problems = []
    for version, path in migrations():
        if version in seen:
            problems.append(f"V{version:04d} used by {seen[version]} and {path.name}")
        seen[version] = path.name
    result("flyway: migration versions are unique", problems)


def check_grants() -> None:
    """Flyway: every created table is granted to the application role.

    A `GRANT ... ON ALL TABLES IN SCHEMA s` only covers tables that exist when it
    runs, so it counts only for tables created in that migration or earlier. Nine
    migrations got this wrong before V0035; this check keeps it from recurring.
    """
    created: list[tuple[int, str, str, Path]] = []  # version, schema, table, path
    explicit: dict[tuple[str, str], int] = {}       # (schema, table) -> version
    blanket: dict[str, int] = {}                    # schema -> earliest blanket version
    dropped: set[tuple[str, str]] = set()

    # A declarative partition is reached through its parent, which carries the
    # grant, so `CREATE TABLE x.y PARTITION OF ...` needs no grant of its own.
    create_re = re.compile(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([a-z_]+)\.([a-z_0-9]+)", re.I)
    partition_of = re.compile(r"\s*PARTITION\s+OF\b", re.I)
    drop_re = re.compile(
        r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?([a-z_]+)\.([a-z_0-9]+)", re.I)
    blanket_re = re.compile(
        r"GRANT\s+[^;]*?\bON\s+ALL\s+TABLES\s+IN\s+SCHEMA\s+([a-z_]+)\s+TO\s+horecaos_application",
        re.I | re.S)
    explicit_re = re.compile(
        r"GRANT\s+[^;]*?\bON\s+(?:TABLE\s+)?([a-z_]+)\.([a-z_0-9]+)\s+TO\s+horecaos_application",
        re.I | re.S)

    for version, path in migrations():
        text = path.read_text(errors="replace")
        for m in create_re.finditer(text):
            # A declarative partition is reached through its parent, which holds
            # the grant. Checked after the match so the table-name group cannot
            # backtrack the exclusion away.
            if partition_of.match(text, m.end()):
                continue
            # Dynamic DDL inside a PL/pgSQL string literal (partition creation
            # functions) names tables that do not exist yet at migration time.
            line_start = text.rfind("\n", 0, m.start()) + 1
            if text.count("'", line_start, m.start()) % 2 == 1:
                continue
            created.append((version, m.group(1).lower(), m.group(2).lower(), path))
        for schema in blanket_re.findall(text):
            s = schema.lower()
            blanket[s] = min(blanket.get(s, version), version)
        for schema, table in explicit_re.findall(text):
            key = (schema.lower(), table.lower())
            explicit[key] = min(explicit.get(key, version), version)
        for schema, table in drop_re.findall(text):
            dropped.add((schema.lower(), table.lower()))

    problems = []
    for version, schema, table, path in created:
        if (schema, table) in explicit or (schema, table) in dropped:
            continue
        # A blanket grant covers this table only if it ran at or after creation.
        blanket_versions = [v for s, v in blanket.items() if s == schema and v >= version]
        if blanket_versions:
            continue
        problems.append(f"{schema}.{table} created in {path.name} — no GRANT to horecaos_application")

    result("flyway: every created table has a GRANT that actually covers it", problems,
           "End the migration with an explicit GRANT for each new table.")


def check_timestamptz() -> None:
    """Schema: instants are UTC timestamptz; local time is a separate IANA column."""
    problems = []
    pat = re.compile(r"\b(?:timestamp|time)\s+without\s+time\s+zone\b", re.I)
    for _, path in migrations():
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if pat.search(line):
                problems.append(f"{rel(path)}:{n}: {line.strip()}")
    result("schema: no naive timestamp columns", problems, "Use timestamptz.")


def check_money_columns() -> None:
    """Schema: money is integer minor units plus an ISO currency code."""
    problems = []
    pat = re.compile(
        r"^\s*[a-z_]*(?:amount|price|total|fee|cost|subtotal|balance|charge)[a-z_]*\s+"
        r"(real|double\s+precision|float\d*|money)\b", re.I)
    for _, path in migrations():
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if pat.search(line):
                problems.append(f"{rel(path)}:{n}: {line.strip()}")
    result("schema: no floating-point money columns", problems,
           "Use bigint minor units with an ISO currency code.")


def check_money_fields() -> None:
    """Java: never floating-point money."""
    problems = []
    pat = re.compile(
        r"\b(?:double|float)\s+[a-zA-Z_]*(?:amount|price|total|fee|cost|subtotal|balance)",
        re.I)
    for path in java_files():
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if pat.search(line):
                problems.append(f"{rel(path)}:{n}: {line.strip()}")
    result("java: no floating-point money fields", problems)


def check_domain_purity() -> None:
    """Java: domain code depends on no framework, transport, or provider SDK.

    Hexagonal boundary from AGENTS.md. The Modulith tests cover module edges;
    this covers the inner ring, and runs without a JVM.
    """
    forbidden = re.compile(
        r"^import\s+(org\.springframework\.web|org\.springframework\.http"
        r"|org\.apache\.camel|org\.apache\.kafka|software\.amazon\.awssdk"
        r"|com\.fasterxml\.jackson|jakarta\.servlet|org\.springframework\.jdbc)\b")
    problems = []
    for path in java_files():
        parts = path.parts
        if "domain" not in parts:
            continue
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            m = forbidden.match(line.strip())
            if m:
                problems.append(f"{rel(path)}:{n}: {m.group(1)}")
    result("java: domain layer imports no framework or provider types", problems,
           "Move the dependency into an adapter and map at the boundary.")


def check_provider_branching() -> None:
    """Java: no `if (provider == CLICK)` in core — providers are ports and adapters."""
    problems = []
    # Case-sensitive and anchored: PAYME must not match inside PaymentTender.
    pat = re.compile(
        r"(?:==|!=|equals\(|equalsIgnoreCase\()\s*\"?"
        r"(?:[A-Za-z_]+\.)?(CLICK|PAYME|UZUM|YANDEX|PAYNET)\b\"?")
    # `this == CLICK` inside the provider enum is the capability declaration
    # AGENTS.md asks for, not a branch in core logic.
    self_ref = re.compile(r"\bthis\s*(?:==|!=)")
    for path in java_files():
        parts = path.parts
        if "domain" not in parts and "application" not in parts:
            continue
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if pat.search(line) and not self_ref.search(line):
                problems.append(f"{rel(path)}:{n}: {line.strip()[:110]}")
    result("java: no provider-name branching in domain or application code", problems,
           "Express the difference as a capability on the adapter port.")


def check_secrets() -> None:
    """Secrets: the database stores a reference, and git stores neither (ADR 0028)."""
    try:
        tracked = subprocess.run(
            ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True
        ).stdout.splitlines()
    except Exception as exc:  # not a git checkout
        result("secrets: no credential files tracked", [], f"skipped: {exc}")
        return
    pat = re.compile(r"(^|/)\.env($|\.)|\.pem$|\.p12$|\.jks$|(^|/)id_rsa|application-local-secrets")
    problems = [f for f in tracked if pat.search(f) and not f.endswith(".env.example")]
    result("secrets: no credential files tracked", problems)


def check_adr_statuses() -> None:
    """ADR 0000: every ADR carries a decision status and an implementation status."""
    problems = []
    for path in sorted(ADR.glob("[0-9]*.md")):
        text = path.read_text(errors="replace")
        if not re.search(r"decision status", text, re.I):
            problems.append(f"{rel(path)}: no decision status")
        if not re.search(r"implementation status", text, re.I):
            problems.append(f"{rel(path)}: no implementation status")
    result("adr: every ADR carries both status fields", problems)


def check_print_debugging() -> None:
    """Observability: structured logs only, never stdout."""
    problems = []
    pat = re.compile(r"\bSystem\.(?:out|err)\.print")
    for path in java_files():
        for n, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if pat.search(line):
                problems.append(f"{rel(path)}:{n}: {line.strip()[:110]}")
    result("java: no print debugging in production code", problems)


def check_security_definer_pinning() -> None:
    """Schema: a SECURITY DEFINER function names pg_temp last and qualifies the catalogue.

    `SET search_path = pg_catalog` reads like a pin and is not one. PostgreSQL
    searches the session's temporary schema BEFORE every entry in `search_path` —
    `pg_catalog` included — for relation and type names, unless `pg_temp` is
    written in the path explicitly, which puts it where it is written. All four of
    this platform's SECURITY DEFINER functions omitted it, and all four read the
    catalogue through bare names, so a caller holding TEMPORARY (the PUBLIC
    default, never revoked until V0080) could create a table called `pg_class` and
    that table was what the function read. A forged row carrying today's partition
    name and an expired partition's bound made
    `fulfillment.sweep_expired_track_partitions` drop a live day of ADR 0029
    courier tracks.

    Two rules, because the arrangement that failed was one declaration being right
    in four places: name `pg_temp` last, AND schema-qualify the catalogue anyway.

    Only the LATEST definition of each function counts. Migrations are append-only,
    so V0031's and V0075's superseded bodies stay on disk and are not defects; this
    reads them in version order and judges what the database would end up with.
    Note that `CREATE OR REPLACE` drops any SET clause it does not restate, which
    is modelled here — a replace with no `SET search_path` unpins the function.

    This is the fast pre-flight. DatabasePrivilegeTests is the authority: it reads
    `pg_proc` on a migrated database, so it sees what the engine actually holds
    rather than what the text says.
    """
    define = re.compile(
        r"CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+([a-z_]+)\.([a-z_0-9]+)\s*\(",
        re.IGNORECASE)
    alter = re.compile(
        r"ALTER\s+FUNCTION\s+([a-z_]+)\.([a-z_0-9]+)\s*\((.*?)\)(.*?);",
        re.IGNORECASE | re.DOTALL)
    path_of = re.compile(r"SET\s+search_path\s*(?:=|TO)\s*([^\n;]+)", re.IGNORECASE)
    bare = re.compile(
        r"(?<!pg_catalog\.)\bpg_(class|namespace|inherits|proc|attribute|constraint|index"
        r"|indexes|depend|type|tables|views|matviews|roles|database|partitioned_table"
        r"|authid|shdepend|rewrite|trigger|extension)\b",
        re.IGNORECASE)

    # name -> (secdef, search_path or None, body, migration file)
    state: dict[str, tuple[bool, str | None, str, str]] = {}
    for _, path in migrations():
        sql = path.read_text(errors="replace")
        for m in define.finditer(sql):
            name = f"{m.group(1).lower()}.{m.group(2).lower()}"
            end = sql.find("$$;", m.end())
            block = sql[m.start():end if end > 0 else len(sql)]
            header = block.split("AS $$", 1)[0]
            body = block.split("AS $$", 1)[1] if "AS $$" in block else ""
            declared = path_of.search(header)
            state[name] = (
                bool(re.search(r"SECURITY\s+DEFINER", header, re.IGNORECASE)),
                declared.group(1).strip() if declared else None,
                body,
                path.name,
            )
        for m in alter.finditer(sql):
            name = f"{m.group(1).lower()}.{m.group(2).lower()}"
            if name not in state:
                continue
            secdef, search_path, body, _ = state[name]
            clause = m.group(4)
            declared = path_of.search(clause)
            state[name] = (
                secdef or bool(re.search(r"SECURITY\s+DEFINER", clause, re.IGNORECASE)),
                declared.group(1).strip() if declared else search_path,
                body,
                path.name,
            )

    problems = []
    for name, (secdef, search_path, body, where) in sorted(state.items()):
        if not secdef:
            continue
        if search_path is None:
            problems.append(f"{name} ({where}): SECURITY DEFINER with no SET search_path")
        else:
            entries = [e.strip() for e in search_path.split(",") if e.strip()]
            if not entries or entries[-1].lower() != "pg_temp":
                problems.append(
                    f"{name} ({where}): search_path = {search_path} — pg_temp must be LAST; "
                    f"omitting it makes the temporary schema FIRST")
        stripped = re.sub(r"--[^\n]*", "", body)
        for hit in sorted({m.group() for m in bare.finditer(stripped)}):
            problems.append(f"{name} ({where}): reads {hit} unqualified — write pg_catalog.{hit}")

    definers = sum(1 for secdef, *_ in state.values() if secdef)
    result(f"schema: SECURITY DEFINER functions pin pg_temp last ({definers} functions)",
           problems,
           "SET search_path = pg_catalog, pg_temp — and schema-qualify every catalogue "
           "read anyway, because one declaration being right in four places is the "
           "arrangement V0080 had to repair.")


def check_link_repair() -> None:
    """Tools: the ADR link repairer covers every relative link, not only .md.

    doc_links.py catches a broken link after it is written; this catches the
    repairer failing to see a whole class of link at all, which is how three
    `.sql` and directory links stayed broken through runs that reported success.
    See link_repair_selftest.py.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from link_repair_selftest import CASES, problems as link_problems
    result(f"tools: ADR link repair covers every relative target ({len(CASES)} cases)",
           link_problems(),
           "tools/file-adrs must re-base a link to a file, a directory, or any "
           "extension — see tools/checks/link_repair_selftest.py")


def check_tenant_scoped_references() -> None:
    """Schema: a foreign key into a tenant-scoped table names the tenant column.

    From AGENTS.md: unique and foreign keys include tenant_id. A single-column
    reference into a table that carries a tenant lets one tenant's row point at
    another tenant's — which is what fulfillment.courier_engagements did to
    media.assets from V0040 until V0069, storing a pointer at another tenant's
    private registration certificate and turning the endpoint that wrote it into
    an existence oracle for asset ids. V0058 had prepared the two-column key and
    V0065's sweep still missed this one, because nothing here could see it.

    See tools/checks/tenant_scoped_references.py, which reads the migrations in
    version order so a later DROP/ADD pair counts as the fix it is. It is the fast
    pre-flight only: it matches DDL text, and text matching is how the
    `ADD tenant_id` spelling hid a whole table's worth of references from it.
    TenantScopedReferenceCatalogTests is the authority — it reads `pg_constraint`
    on a migrated database and checks the same allowlist file.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from tenant_scoped_references import load_allowlist, problems as fk_problems
    allowed, _ = load_allowlist()
    result(f"schema: foreign keys into tenant-scoped tables carry tenant_id "
           f"({len(allowed)} known gaps, each with a reason, list may only shrink)",
           fk_problems(),
           "Reference the target's (id, tenant_id) unique constraint. A foreign key "
           "must reference a unique constraint on exactly its own columns.")


CHECKS = [
    check_unique_versions,
    check_grants,
    check_timestamptz,
    check_money_columns,
    check_money_fields,
    check_domain_purity,
    check_provider_branching,
    check_secrets,
    check_adr_statuses,
    check_print_debugging,
    check_security_definer_pinning,
    check_link_repair,
    check_tenant_scoped_references,
]


def main() -> int:
    for check in CHECKS:
        check()
    print()
    if failures:
        print(f"repo-hygiene: {len(failures)} check(s) failed. Each maps to a rule in AGENTS.md.")
        return 1
    print(f"repo-hygiene: clean ({len(CHECKS)} checks)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
