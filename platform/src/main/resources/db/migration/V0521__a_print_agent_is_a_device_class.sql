-- ADR 0154, Decision 9: a print agent is an ADR 0079 device principal.
--
-- DevicePrincipalClass gains PRINT_AGENT. Both CHECKs the device primitive carries are restated in
-- full and never an ALTER that only adds a value (the V0145 and V0501 precedent): a dropped and
-- recreated constraint has to carry every value that was already legal, or a live row of an
-- existing class fails the rewrite.
--
-- Every class in force on this branch's base is carried: KITCHEN_KDS (V0192) and KITCHEN_VDU (V0501).
-- ADR 0151 added the second; ADR 0155 and ADR 0162 add device classes of their own in parallel
-- waves, and the last of the three migrations to merge carries all of them -- this file is the one
-- an integrator widens, in place, before it merges, and PrintingMigrationTests asserts the whole list
-- it holds.
ALTER TABLE iam.device_principals DROP CONSTRAINT ck_device_principal_class;
ALTER TABLE iam.device_principals
    ADD CONSTRAINT ck_device_principal_class CHECK (device_class IN ('KITCHEN_KDS', 'KITCHEN_VDU', 'PRINT_AGENT'));

ALTER TABLE iam.device_enrolment_requests DROP CONSTRAINT ck_device_enrolment_class;
ALTER TABLE iam.device_enrolment_requests
    ADD CONSTRAINT ck_device_enrolment_class CHECK (requested_class IN ('KITCHEN_KDS', 'KITCHEN_VDU', 'PRINT_AGENT'));
