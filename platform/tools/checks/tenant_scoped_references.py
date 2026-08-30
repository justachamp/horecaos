#!/usr/bin/env python3
"""Foreign keys into a tenant-scoped table must carry the tenant column.

The rule is in AGENTS.md and CLAUDE.md: every tenant-owned row carries a
non-null `tenant_id`, and unique and foreign keys include it. A reference that
omits it is not a typo with a cosmetic cost — it is the whole tenant boundary,
absent. `fulfillment.courier_engagements.evidence_media_id` referenced
`media.assets (asset_id)` on one column from V0040 until V0069, which let one
tenant durably store a pointer at another tenant's private registration
certificate, and turned the endpoint that wrote it into a platform-wide
existence oracle for media asset ids. V0058 had already added the two-column
unique for exactly this, and V0065 swept two of the three references onto it.
One was missed, and nothing in the build could tell.

This module finds every such reference by reading the migrations the way
PostgreSQL would: constraints are added and dropped in version order, and only
what survives to the end is judged. That matters because the fix for one of
these is a `DROP CONSTRAINT` and an `ADD CONSTRAINT` in a later migration, and a
plain grep would still see the defective text in the migration that first
created it.

It is a text matcher, and a text matcher is a guess about DDL. This one guessed
wrong once already: it recognised `ADD COLUMN tenant_id` and not `ADD tenant_id`,
which is the same statement with one optional keyword left out, so a table that
gained its tenant column that way never entered `tenant_scoped` and every blind
reference out of it was invisible. That spelling is handled now and both are in
the selftests, but the general problem does not go away — `CREATE TABLE ... LIKE`,
a column added inside a DO block, a table inherited from another all mean
something this file cannot see.

So this module is the fast pre-flight, not the authority.
`TenantScopedReferenceCatalogTests` is the authority: it migrates a real database
and enumerates `pg_constraint`, which is what PostgreSQL actually believes, and
compares it against the same allowlist file this module reads. When the two
disagree, the catalog is right and this module has a bug.

Imported by repo_hygiene.py; runnable on its own to print the current set.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIG = ROOT / "src/main/resources/db/migration"

# The tenant registry itself. `tenant_id -> tenant.tenants (id)` is what makes a
# row tenant-scoped in the first place; requiring it to name a tenant column
# twice would be circular.
TENANT_REGISTRY = "tenant.tenants"

# The allowlist lives in a file beside this one, not in a set here. Two reasons,
# both learned from the twenty-three it used to hold as bare names:
#
#   1. Every entry must carry a verdict and a reason. A bare name is unarguable
#      and unreviewable, and twenty-three of them under one constant is how a
#      finding turns into furniture. `_load_allowlist` refuses an entry without
#      one, so the file cannot regress to a list of names.
#   2. `TenantScopedReferenceCatalogTests` reads the same file and checks it
#      against `pg_constraint` on a migrated database. A Python set could not be
#      shared with the JVM side, and two allowlists would drift.
#
# The list may only shrink. Adding a new tenant-blind reference fails the build;
# so does leaving an entry here after the reference is fixed or renamed, so that
# closing one of these cannot be done quietly or claimed without being done.
#
# The two-column unique to reference is usually already there — look for a
# `uq_*_identity UNIQUE (id, tenant_id)` on the target — and a foreign key must
# reference a unique constraint on exactly its own columns, so a three-column
# unique will not serve a two-column reference.
ALLOWLIST = Path(__file__).resolve().parent / "known_tenant_blind_references.tsv"

VERDICTS = {"TENANT_FREE_TARGET", "MIXED_OWNERSHIP_TARGET", "DEFERRED"}

# Long enough that a reason has to be a sentence about this reference rather than
# a shrug. "legacy", "safe", "TODO" and "by design" are all shorter than this.
_MIN_REASON = 80


def load_allowlist() -> tuple[dict[str, tuple[str, str]], list[str]]:
    """The reference -> (verdict, reason) map, and any complaints about the file."""
    entries: dict[str, tuple[str, str]] = {}
    complaints: list[str] = []
    if not ALLOWLIST.exists():
        return entries, [f"{ALLOWLIST.name} is missing"]
    for number, line in enumerate(ALLOWLIST.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            complaints.append(
                f"{ALLOWLIST.name}:{number}: expected "
                f"'reference<TAB>verdict<TAB>reason', got {len(parts)} tab-separated field(s)")
            continue
        reference, verdict, reason = (part.strip() for part in parts)
        if verdict not in VERDICTS:
            complaints.append(f"{ALLOWLIST.name}:{number}: {reference} has verdict "
                              f"{verdict!r}, not one of {sorted(VERDICTS)}")
        if len(reason) < _MIN_REASON:
            complaints.append(
                f"{ALLOWLIST.name}:{number}: {reference} has no real reason — "
                f"{len(reason)} characters, and an allowlist of bare names is how "
                f"twenty-three of these became invisible")
        if reference in entries:
            complaints.append(f"{ALLOWLIST.name}:{number}: {reference} listed twice")
        entries[reference] = (verdict, reason)
    return entries, complaints


_NAME = r"([a-z_][a-z_0-9]*)"
_CREATE = re.compile(rf"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?{_NAME}\.{_NAME}", re.I)
_ALTER = re.compile(rf"ALTER\s+TABLE\s+(?:ONLY\s+)?(?:IF\s+EXISTS\s+)?{_NAME}\.{_NAME}\b", re.I)
_ADD_FK = re.compile(
    rf"ADD\s+CONSTRAINT\s+{_NAME}\s+FOREIGN\s+KEY\s*\(([^)]*)\)\s*"
    rf"REFERENCES\s+{_NAME}\.{_NAME}\s*(?:\(([^)]*)\))?",
    re.I | re.S)
_DROP_CONSTRAINT = re.compile(rf"DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?{_NAME}", re.I)
# `COLUMN` is optional in PostgreSQL's ALTER TABLE ... ADD, and this used to
# require it. A table that gained its tenant column as `ADD tenant_id uuid` —
# ordinary, legal DDL — never entered `tenant_scoped`, so every blind reference
# out of it was invisible to this whole module. Reproduced with a probe
# migration that passed `repo_hygiene.py` clean while `pg_constraint` reported
# the constraint as tenant-blind; the last two selftests below are that probe.
#
# A type must follow the name, which is what keeps `ADD CONSTRAINT ...` and
# `ADD UNIQUE (id, tenant_id)` out of it — neither is followed by `tenant_id`
# and a word. That is also the honest limit of a text matcher, and the reason
# TenantScopedReferenceCatalogTests exists: it asks the catalog instead.
_ADD_TENANT_COLUMN = re.compile(
    r"ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?tenant_id\s+[a-z]", re.I)
_SET_SCHEMA = re.compile(rf"SET\s+SCHEMA\s+{_NAME}", re.I)
_RENAME_TABLE = re.compile(rf"RENAME\s+TO\s+{_NAME}", re.I)
_INLINE_FK = re.compile(
    rf"CONSTRAINT\s+{_NAME}\s+FOREIGN\s+KEY\s*\(([^)]*)\)\s*"
    rf"REFERENCES\s+{_NAME}\.{_NAME}\s*(?:\(([^)]*)\))?",
    re.I | re.S)
# `some_id uuid NOT NULL REFERENCES sch.tbl (col)` — a foreign key with no name
# of its own, which PostgreSQL names after the column.
_COLUMN_FK = re.compile(
    rf"(?:^|,)\s*{_NAME}\s+[a-z][a-z0-9_ ]*?\s*(?:\([^)]*\))?[^,;]*?"
    rf"\bREFERENCES\s+{_NAME}\.{_NAME}\s*(?:\(([^)]*)\))?",
    re.I | re.M)
# A column definition, not a mention: `tenant_id uuid` at the start of a line or
# after a comma. Requiring the type keeps `UNIQUE (id, tenant_id)` out of it.
_TENANT_COLUMN = re.compile(r"(?:^|,)\s*tenant_id\s+uuid\b", re.I | re.M)

# A column that IS the tenant without being spelled `tenant_id`.
#
# V0088 and V0089 close "the platform's row or my own" with a generated column —
# `owner_tenant_id GENERATED ALWAYS AS (coalesce(tenant_id, <nil>)) STORED` on the
# target, and a matching derived column on the referencing side — because a
# composite key cannot express an OR and a trigger loses the race (V0086 lost it
# twice, reproduced). Those references carry the tenant in every sense that
# matters and the database enforces them, but the columns are not named
# `tenant_id`, so a check matching on the name reports five closed references as
# open. An allowlist that lists things which are not open stops being read.
#
# The expression has to mention tenant_id for this to fire, so a generated column
# derived from anything else is not mistaken for the tenant.
_DERIVED_TENANT_COLUMN = re.compile(
    r"(?:^|,)\s*([a-z_0-9]+)\s+uuid\s+GENERATED\s+ALWAYS\s+AS\s*(.*?)\bSTORED\b",
    re.I | re.S)
_ADD_DERIVED_TENANT_COLUMN = re.compile(
    r"ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?([a-z_0-9]+)\s+uuid\s+"
    r"GENERATED\s+ALWAYS\s+AS\s*(.*?)\bSTORED\b",
    re.I | re.S)


def _derived_from_tenant(matches) -> list[str]:
    """The generated columns whose expression actually mentions the tenant.

    Deliberately not a balanced-paren regex. The first attempt was one, and it
    failed on the very expression it was written for: `coalesce(tenant_id, <nil>)`
    was swallowed whole by the nested-group alternative before the literal could
    match, so the check went on reporting a closed reference as open. Capturing
    the expression and looking inside it is duller and does not have that failure.
    """
    return [name.lower() for name, expression in matches
            if re.search(r"\btenant_id\b", expression, re.I)]


def _strip_comments(text: str) -> str:
    """Drops `--` comments, leaving anything after a `--` inside a string alone."""
    out = []
    for line in text.splitlines():
        cut = -1
        for match in re.finditer(r"--", line):
            if line.count("'", 0, match.start()) % 2 == 0:
                cut = match.start()
                break
        out.append(line if cut < 0 else line[:cut])
    return "\n".join(out)


def _body(text: str, start: int) -> tuple[str, int] | None:
    """The parenthesised column list of a CREATE TABLE, and where it ends."""
    open_at = text.find("(", start)
    if open_at < 0:
        return None
    # A `CREATE TABLE x PARTITION OF y ...` has no column list of its own.
    if re.match(r"\s*PARTITION\s+OF\b", text[start:open_at], re.I):
        return None
    depth = 0
    in_string = False
    for i in range(open_at, len(text)):
        ch = text[i]
        if ch == "'":
            in_string = not in_string
        elif not in_string:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return text[open_at + 1:i], i
    return None


def _cols(raw: str | None) -> list[str]:
    if raw is None:
        return []
    return [c.strip().lower() for c in raw.split(",") if c.strip()]


def _migrations() -> list[tuple[int, Path]]:
    out = []
    for path in sorted(MIG.glob("V*__*.sql")):
        m = re.match(r"V(\d+)__", path.name)
        if m:
            out.append((int(m.group(1)), path))
    return sorted(out)


def surviving_references() -> tuple[dict[str, dict], set[str]]:
    """Every foreign key still in force, and the set of tenant-scoped tables.

    Applied in version order so that a later `DROP CONSTRAINT` / `ADD CONSTRAINT`
    pair — the only way to widen a foreign key's column list — is read as the
    correction it is rather than as a second defect.
    """
    return _parse(*((path.name, path.read_text(errors="replace"))
                    for _, path in _migrations()))


def _parse(*sources: str | tuple[str, str]) -> tuple[dict[str, dict], set[str]]:
    """Applies DDL in the order given. A bare string is named for the self-test."""
    tenant_scoped: set[str] = set()
    fks: dict[str, dict] = {}
    derived: dict[str, set[str]] = {}

    for source in sources:
        name_of_source, raw = source if isinstance(source, tuple) else ("selftest", source)
        text = _strip_comments(raw)

        # CREATE TABLE: the tenant column, and the constraints declared inline.
        pos = 0
        while True:
            m = _CREATE.search(text, pos)
            if not m:
                break
            table = f"{m.group(1).lower()}.{m.group(2).lower()}"
            found = _body(text, m.end())
            pos = m.end() if not found else found[1]
            if not found:
                continue
            body = found[0]
            if _TENANT_COLUMN.search(body):
                tenant_scoped.add(table)
            for column in _derived_from_tenant(_DERIVED_TENANT_COLUMN.findall(body)):
                derived.setdefault(table, set()).add(column)
            for name, src, ref_schema, ref_table, ref_cols in _INLINE_FK.findall(body):
                fks[f"{table}.{name.lower()}"] = {
                    "table": table, "columns": _cols(src),
                    "references": f"{ref_schema.lower()}.{ref_table.lower()}",
                    "referenced_columns": _cols(ref_cols), "migration": name_of_source,
                }
            named = {m2.group(1).lower() for m2 in re.finditer(
                rf"CONSTRAINT\s+{_NAME}", body, re.I)}
            for column, ref_schema, ref_table, ref_cols in _COLUMN_FK.findall(body):
                if column.lower() in named or column.lower() == "constraint":
                    continue
                key = f"{table}.{column.lower()}_fkey"
                fks[key] = {
                    "table": table, "columns": [column.lower()],
                    "references": f"{ref_schema.lower()}.{ref_table.lower()}",
                    "referenced_columns": _cols(ref_cols), "migration": name_of_source,
                }

        # ALTER TABLE: each statement belongs to the table its ALTER named.
        alters = list(_ALTER.finditer(text))
        for i, m in enumerate(alters):
            table = f"{m.group(1).lower()}.{m.group(2).lower()}"
            end = alters[i + 1].start() if i + 1 < len(alters) else len(text)
            statement = text[m.end():end]
            statement = statement[:statement.find(";") + 1 or len(statement)]
            if _ADD_TENANT_COLUMN.search(statement):
                tenant_scoped.add(table)
            for column in _derived_from_tenant(_ADD_DERIVED_TENANT_COLUMN.findall(statement)):
                derived.setdefault(table, set()).add(column)
            for name in _DROP_CONSTRAINT.findall(statement):
                fks.pop(f"{table}.{name.lower()}", None)
            for name, src, ref_schema, ref_table, ref_cols in _ADD_FK.findall(statement):
                fks[f"{table}.{name.lower()}"] = {
                    "table": table, "columns": _cols(src),
                    "references": f"{ref_schema.lower()}.{ref_table.lower()}",
                    "referenced_columns": _cols(ref_cols), "migration": name_of_source,
                }

            # A table that moves schema or is renamed keeps its constraints and
            # everything that points at it. Without this, V0039's move of
            # fiscal_documents out of `payments` leaves the check judging a
            # table under a name PostgreSQL no longer knows.
            moved = _SET_SCHEMA.search(statement)
            renamed = None if moved else _RENAME_TABLE.search(statement)
            if moved or renamed:
                new = (f"{moved.group(1).lower()}.{table.split('.')[1]}" if moved
                       else f"{table.split('.')[0]}.{renamed.group(1).lower()}")
                _rename(fks, tenant_scoped, table, new)

    # Decided here rather than at the point each key was parsed, because the
    # generated column is usually added by a later ALTER than the CREATE that
    # declared the key — V0089 adds iam.roles.owner_tenant_id three statements
    # after the table it belongs to already existed.
    #
    # BOTH sides must carry it. A referencing column derived from its own tenant
    # pointing at a target column derived from the target's tenant is the whole
    # V0088 shape; one side alone proves nothing.
    for fk in fks.values():
        source_derived = derived.get(fk["table"], set())
        target_derived = derived.get(fk["references"], set())
        fk["carries_tenant"] = (
            "tenant_id" in fk["columns"]
            or "tenant_id" in fk["referenced_columns"]
            or (any(c in source_derived for c in fk["columns"])
                and any(c in target_derived for c in fk["referenced_columns"]))
        )

    return fks, tenant_scoped


def _rename(fks: dict[str, dict], tenant_scoped: set[str], old: str, new: str) -> None:
    """Carries a table's constraints, and every reference to it, to its new name."""
    if old in tenant_scoped:
        tenant_scoped.discard(old)
        tenant_scoped.add(new)
    for key in [k for k in fks if fks[k]["table"] == old]:
        fk = fks.pop(key)
        fk["table"] = new
        fks[f"{new}.{key.rsplit('.', 1)[1]}"] = fk
    for fk in fks.values():
        if fk["references"] == old:
            fk["references"] = new


def tenant_blind() -> dict[str, dict]:
    """References from one tenant-scoped table into another that omit tenant_id."""
    return _blind_in(surviving_references())


def _blind_in(parsed: tuple[dict[str, dict], set[str]]) -> dict[str, dict]:
    fks, tenant_scoped = parsed
    blind = {}
    for key, fk in fks.items():
        if fk["table"] not in tenant_scoped:
            continue
        if fk["references"] not in tenant_scoped or fk["references"] == TENANT_REGISTRY:
            continue
        if fk.get("carries_tenant"):
            continue
        blind[key] = fk
    return blind


# A check nobody has watched fail is a check that reports success. This is the
# same lesson V0065 taught: its sweep believed itself exhaustive and missed one.
# These cases run on every lint, cost microseconds, and would have caught a
# parser that silently stopped seeing foreign keys at all.
_SELFTEST = [
    ("a single-column reference into a tenant-scoped table is caught", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            thing_id uuid,
            CONSTRAINT fk_other_thing FOREIGN KEY (thing_id) REFERENCES a.thing (id));
     """, True),
    ("a composite reference is not", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
            CONSTRAINT uq UNIQUE (id, tenant_id));
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            thing_id uuid,
            CONSTRAINT fk_other_thing FOREIGN KEY (thing_id, tenant_id)
                REFERENCES a.thing (id, tenant_id));
     """, False),
    ("a later DROP and re-ADD is read as the correction it is", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            thing_id uuid,
            CONSTRAINT fk_other_thing FOREIGN KEY (thing_id) REFERENCES a.thing (id));
        ALTER TABLE b.other DROP CONSTRAINT fk_other_thing;
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_id, tenant_id) REFERENCES a.thing (id, tenant_id);
     """, False),
    ("an unnamed column-level reference is seen too", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            thing_id uuid NOT NULL REFERENCES a.thing (id));
     """, True),
    ("a reference into a table with no tenant of its own is not this rule's business", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY);
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            thing_id uuid,
            CONSTRAINT fk_other_thing FOREIGN KEY (thing_id) REFERENCES a.thing (id));
     """, False),
    ("tenant_id -> tenant.tenants (id) is what makes a row scoped, not a breach of it", """
        CREATE TABLE b.other (
            id uuid PRIMARY KEY,
            tenant_id uuid NOT NULL,
            CONSTRAINT fk_other_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id));
     """, False),
    # The hole. Both spellings mean the same thing to PostgreSQL, and only one of
    # them used to mean anything here.
    ("a table made tenant-scoped by ALTER ... ADD COLUMN tenant_id is judged", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (id uuid PRIMARY KEY, thing_id uuid);
        ALTER TABLE b.other ADD COLUMN tenant_id uuid NOT NULL;
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_id) REFERENCES a.thing (id);
     """, True),
    ("and so is one made tenant-scoped by ALTER ... ADD tenant_id", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (id uuid PRIMARY KEY, thing_id uuid);
        ALTER TABLE b.other ADD tenant_id uuid NOT NULL;
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_id) REFERENCES a.thing (id);
     """, True),
    ("an ADD CONSTRAINT naming tenant_id does not make a table tenant-scoped", """
        CREATE TABLE a.thing (id uuid PRIMARY KEY, tenant_id uuid NOT NULL);
        CREATE TABLE b.other (id uuid PRIMARY KEY, thing_id uuid);
        ALTER TABLE b.other ADD CONSTRAINT uq_other_identity UNIQUE (id, tenant_id);
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_id) REFERENCES a.thing (id);
     """, False),

    # The derived-owner shape V0088 and V0089 use, and the three ways of getting
    # it wrong. These exist because the case above them was widened to accept a
    # column that IS the tenant without being called tenant_id, and a widening
    # nobody has watched refuse something is a widening that accepts everything.
    ("a derived owner on both sides carries the tenant", """
        CREATE TABLE a.thing (
            id uuid PRIMARY KEY, tenant_id uuid,
            owner_tenant_id uuid GENERATED ALWAYS AS (
                coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED);
        CREATE TABLE b.other (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, thing_id uuid);
        ALTER TABLE b.other ADD COLUMN thing_owner_id uuid GENERATED ALWAYS AS (
            CASE WHEN false THEN '00000000-0000-0000-0000-000000000000'::uuid
                 ELSE tenant_id END) STORED;
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_owner_id, thing_id) REFERENCES a.thing (owner_tenant_id, id);
     """, False),
    ("a derived column on one side only does not carry it", """
        CREATE TABLE a.thing (
            id uuid PRIMARY KEY, tenant_id uuid,
            owner_tenant_id uuid GENERATED ALWAYS AS (
                coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED);
        CREATE TABLE b.other (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, thing_id uuid);
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_id) REFERENCES a.thing (id);
     """, True),
    ("a generated column derived from something else is not the tenant", """
        CREATE TABLE a.thing (
            id uuid PRIMARY KEY, tenant_id uuid NOT NULL, brand_id uuid,
            owner_tenant_id uuid GENERATED ALWAYS AS (
                coalesce(brand_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED);
        CREATE TABLE b.other (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, thing_id uuid);
        ALTER TABLE b.other ADD COLUMN thing_owner_id uuid GENERATED ALWAYS AS (
            coalesce(brand_id, '00000000-0000-0000-0000-000000000000'::uuid)) STORED;
        ALTER TABLE b.other ADD CONSTRAINT fk_other_thing
            FOREIGN KEY (thing_owner_id, thing_id) REFERENCES a.thing (owner_tenant_id, id);
     """, True),
]


def selftest_problems() -> list[str]:
    """Proves the parser still fires, and still stays quiet, on known shapes."""
    out = []
    for name, sql, should_flag in _SELFTEST:
        flagged = bool(_blind_in(_parse(sql)))
        if flagged is not should_flag:
            out.append(f"selftest: {name} — "
                       f"{'expected a finding, got none' if should_flag else 'flagged a correct reference'}")
    return out


def problems() -> list[str]:
    blind = tenant_blind()
    found = set(blind)
    allowed, out = load_allowlist()
    out = list(out) + selftest_problems()
    for key in sorted(found - set(allowed)):
        fk = blind[key]
        out.append(
            f"{fk['migration']}: {key} references {fk['references']} "
            f"({', '.join(fk['referenced_columns']) or 'its primary key'}) without tenant_id — "
            f"one tenant's row can point at another tenant's")
    for key in sorted(set(allowed) - found):
        out.append(
            f"{key} is listed in {ALLOWLIST.name} but no longer exists — "
            f"remove its line, and say in the commit what closed it")
    return out


if __name__ == "__main__":
    fks, scoped = surviving_references()
    blind = tenant_blind()
    print(f"{len(fks)} foreign keys, {len(scoped)} tenant-scoped tables, "
          f"{len(blind)} tenant-blind references")
    for key in sorted(blind):
        print(f"  {key} -> {blind[key]['references']} ({blind[key]['migration']})")
    found = problems()
    for p in found:
        print(f"PROBLEM {p}")
    sys.exit(1 if found else 0)
