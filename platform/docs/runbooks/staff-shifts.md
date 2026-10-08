# Staff shifts and payroll hours (ADR 0156)

**Last executed:** never. `StaffShiftServiceTests`, `StaffRosterServiceTests`,
`StaffTodayServiceTests`, `WorkforceConstraintTests`, `StaffAttendanceExportTests` and
`WorkforceEndpointTests` exercise every step below against a real PostgreSQL and the real HTTP
stack, with the clock moved by hand. No real tenant has switched attendance on yet, so the
queries here have never been read against production data. Update this line with the date, the
host and what it found the first time it is run.

**Is there a way back?** Yes. `workforce.attendance.enabled` back to `false` makes every
workforce route answer as absent and the export an unknown report. Nothing is deleted: the rota,
the shifts and the payroll facts stay where they are, and switching it on again finds them.

## Before switching it on for a real company

ADR 0156 leaves three questions to legal, and the first one gates production:

1. **Is recording a person's hours workplace monitoring that needs the company to notify its
   staff first?** The notice the owner reads when switching on carries the platform's default
   wording, and says so in its last line. **Do not switch attendance on for a production tenant
   until legal has supplied the wording** and it has replaced the default. The pilot tenant on
   pre-prod can use the default.
2. Whether hours must be retained for a statutory period (the shift rows are never deleted, so
   this is a statement about what the sweeper must never do, not a feature).
3. Whether the hours export is itself a regulated document.

The switch is the owner's: Staff → Смены, «Включить…», tick that the staff were told, give a
reason. The reason goes on the audited configuration write (`workforce.attendance.enabled`,
ADR 0030). The same key can be set from Settings.

## A shift left open

A person forgot to end their shift. The Смены screen's «Сегодня» tab shows it as «Смена открыта
слишком долго» once it has been open longer than `workforce.shift.max_open_hours` (default 14),
including a shift from yesterday: the screen exists to flag it.

**To close it** (a manager with `staff.shift.manage`): «Сегодня» → «Отметить уход», or
«Табель» → «Исправить». A time in the past needs a reason from the list («Забыл отметить
уход»). A shift older than `workforce.shift.retroactive_edit_days` (default 7) can be corrected
only by someone who also holds `staff.shift.approve`. The platform never closes a shift on its
own and never guesses an end.

**To count them across the company** (counts and ids only, no names):

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT tenant_id, location_id, count(*) AS open_shifts,
       min(started_at) AS oldest_start
  FROM workforce.staff_shifts
 WHERE status = 'OPEN' AND started_at < now() - interval '14 hours'
 GROUP BY tenant_id, location_id
 ORDER BY oldest_start;
SQL
```

**Check:** no rows means nobody is over the limit. A row's `location_id` is the branch whose
manager closes it; the query does not close anything and nothing here should be updated by hand:
a hand edit has no audit fact.

A shift waiting in **«Ждёт подтверждения»** is a different thing: it was closed, and its paid
hours differ from the plan by more than `workforce.shift.variance_threshold_minutes`. It is out
of the payroll facts until a manager with `staff.shift.approve` approves it, one shift at a time.

## Reading a divergence alert

The nightly close recuts each business day from the shifts and compares the totals with what it
stored. When a shift was corrected, voided or approved **after** its day was closed, the recut
disagrees. The stored figures are left alone on purpose (ADR 0043) and each disagreement is a row
in `reporting.aggregate_divergences` with the metric `staff_hours.shifts`, `staff_hours.worked`
or `staff_hours.paid` and the dimension `location=<branch id>`:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -At -F',' <<'SQL'
SELECT business_date, metric_id, dimension_key, stored_value, recut_value, difference, status
  FROM reporting.aggregate_divergences
 WHERE metric_id LIKE 'staff\_hours.%' AND status = 'OPEN'
 ORDER BY business_date DESC, metric_id
 LIMIT 50;
SQL
```

**Check:** `difference` is `recut_value - stored_value`, in seconds for `worked` and `paid` and in
shifts for `shifts`. A negative `paid` difference is a shift that was shortened or voided after
the close; a positive one is a shift approved or lengthened. The audit trail says which: the
activity log's «Часы смены исправлены», «Смена аннулирована» and «Смена подтверждена после
отклонения от плана» entries for that branch and day carry the old and new times.

**What to do:** a divergence is a finding for the payroll owner, not an outage. If a payroll
export for that day was already sent, the corrected figure belongs in the next one; export again
for the range and compare. Acknowledge the divergence once somebody has decided. Nothing re-opens
a closed day on its own.

## The payroll export refuses, and why

`STAFF_ATTENDANCE` («Часы сотрудников (для зарплаты)» in the export centre, `/statistics/exports`;
the option appears only for someone who holds `staff.shift.read` in a company that records
attendance) is never truncated: a payroll file whose total quietly stops short is worse than no
file.

- **`ATTENDANCE_UNRESOLVED_SHIFTS`**: the range holds shifts that are still open or awaiting
  approval. Resolve them (the two sections above) and ask again, or tick «Включить смены, которые
  ещё идут или ждут подтверждения», which includes them marked with their status and empty paid
  hours.
- **`ATTENDANCE_EXPORT_TOO_LARGE`**: the range is over 20,000 rows (about nine months of a
  100-person company). Ask for a shorter range. The ceiling is not tenant-settable.
- **«Unknown report key»**: attendance is off for the tenant.
- The name and employee-number columns need `staff.shift.export`; without it the file has the
  non-personal reference only.

## Partitions, a hazard for the next fact table

`reporting.fact_staff_shift` is partitioned by month like the other facts, and
`reporting.ensure_fact_partition` has an allowlist of table names (V0530 re-defines it with
`fact_staff_shift` on the list). **A later migration that adds another partitioned fact has to
re-define the function again and keep every name already on the list**, or the monthly partition
job refuses `fact_staff_shift` and the close fails for the month after the last pre-made
partition. The symptom is a close run that fails with
`reporting.ensure_fact_partition does not manage fact_staff_shift`.
