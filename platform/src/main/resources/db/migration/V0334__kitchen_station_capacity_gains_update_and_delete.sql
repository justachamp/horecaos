-- ADR 0041, frontend-information-architecture.md §2.6, gap map row 2.6 (wave T02).
--
-- V0144 granted the application role SELECT and INSERT on
-- kitchen.station_capacity and stopped there, matching KitchenStationController
-- as it stood: create and list only. That made a mistyped ceiling permanent —
-- there was no way to correct one — and because a second overlapping window is
-- refused (kitchen.station_capacity's own overlap check, enforced in
-- KitchenStationService before every insert), a typo also blocked the correct
-- window from ever being authored: nothing could remove the wrong one to free
-- the slot.
--
-- KitchenStationService.updateCapacityWindow and .deleteCapacityWindow now
-- exist, each a conditional UPDATE/DELETE naming the version the caller last
-- saw, exactly like every other optimistic-locked write in this module. Both
-- need the privileges this migration grants; DatabasePrivilegeTests'
-- theGrantsCoverWhatTheCodeActuallyDoes scans src/main/java for the UPDATE and
-- DELETE statements and would fail the build without this.

GRANT UPDATE, DELETE ON kitchen.station_capacity TO horecaos_application;
