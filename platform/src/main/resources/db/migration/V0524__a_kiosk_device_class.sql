-- ADR 0162: a third device class, KIOSK -- a customer-facing screen bound to one KIOSK sales channel at one
-- branch, paired by a code the manager confirms and granted one read capability.
--
-- ADR 0079 gave the device primitive one class and ADR 0151 added a second; both left the class a CHECK
-- value and nothing else, so adding a class is exactly this: both constraints restated in full, never an
-- ALTER that only adds a value (the V0145 and V0501 precedent). The pair is dropped and recreated with every
-- value that was already legal, KITCHEN_KDS, KITCHEN_VDU and ADR 0154's PRINT_AGENT (V0521, which runs
-- before this one), or a live row of an existing class would fail the rewrite. Whichever migration merges
-- last carries the full list in force, as ADR 0154 says; the integrator found PRINT_AGENT missing here when
-- the two waves met, and every print-agent enrolment was refused by the CHECK until it was restated.
--
-- This migration is the approval correction's schema half and ships alone: nothing else in ADR 0162 is
-- needed for a class to be legal. The kiosk's own table, the tenant-bearing keys it references and the
-- role that grants it come with the migrations after this one.

ALTER TABLE iam.device_principals DROP CONSTRAINT ck_device_principal_class;
ALTER TABLE iam.device_principals
    ADD CONSTRAINT ck_device_principal_class CHECK (device_class IN ('KITCHEN_KDS', 'KITCHEN_VDU', 'PRINT_AGENT', 'KIOSK'));

ALTER TABLE iam.device_enrolment_requests DROP CONSTRAINT ck_device_enrolment_class;
ALTER TABLE iam.device_enrolment_requests
    ADD CONSTRAINT ck_device_enrolment_class CHECK (requested_class IN ('KITCHEN_KDS', 'KITCHEN_VDU', 'PRINT_AGENT', 'KIOSK'));
