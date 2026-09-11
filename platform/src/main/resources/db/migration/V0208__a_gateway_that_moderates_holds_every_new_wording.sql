-- ADR 0091, as decided on 2026-09-11: the VAS SMS gateway is treated as one
-- that moderates wordings, so every new SMS wording a tenant saves for it
-- waits until an operator records the gateway's approval.
--
-- Moderation is a property of the approved gateway endpoint, not of a tenant
-- or of a template: it is the gateway's operator that refuses unapproved
-- texts. A tenant's new SMS version starts PENDING when an SMS binding of that
-- tenant sits on a moderating endpoint, or, while the tenant has no SMS
-- binding yet, when any approved notification endpoint moderates -- the
-- gateway it will use is not known, and a wording that goes out unapproved
-- fails every message it is used for.

ALTER TABLE integration.provider_environments
    ADD COLUMN moderates_wordings boolean NOT NULL DEFAULT false;

UPDATE integration.provider_environments
   SET moderates_wordings = true
 WHERE provider_type = 'SMSGW_VAS';

COMMENT ON COLUMN integration.provider_environments.moderates_wordings IS
    'ADR 0091. The gateway refuses any text its operator has not approved; a new SMS wording for it starts PENDING.';
