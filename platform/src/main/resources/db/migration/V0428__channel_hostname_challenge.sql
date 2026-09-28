-- ADR 0036, row 10.5: the DNS-TXT ownership challenge for a custom channel
-- hostname (V0403's own header: "The automated DNS-TXT challenge-and-poll
-- flow ... is not built here -- this migration and its service give the flag
-- somewhere to live, not the checker." This migration is that checker's own
-- storage.)
--
-- Two nullable columns, not a satellite table: a channel has at most one
-- outstanding challenge at a time, same one-row-per-channel shape
-- `channel_hostnames` itself already has, so a second table keyed the same
-- way (tenant_id, channel_id) would only ever hold zero or one row anyway.
--
-- Null for a platform-issued subdomain (verified immediately -- see
-- ChannelSetupService#setSubdomain -- so there is nothing to challenge) and
-- for a channel with no hostname claimed at all. Populated the moment a
-- custom hostname is claimed (ChannelSetupService#setCustomHostname) and
-- replaced on demand (ChannelSetupService#rotateChallenge) without touching
-- the hostname itself.
--
-- challenge_token is not a secret in the ADR 0028 sense -- it is meant to be
-- copied into a public DNS TXT record -- so it lives in a plain column, not
-- behind secret_reference.
ALTER TABLE tenant.channel_hostnames
    ADD COLUMN challenge_token varchar(64),
    ADD COLUMN challenge_issued_at timestamptz;

COMMENT ON COLUMN tenant.channel_hostnames.challenge_token IS
    'Row 10.5: the value an operator must publish in a `_horecaos-challenge.<hostname>` TXT record to prove control of a custom domain. Null for a platform-issued subdomain or an unclaimed channel.';
COMMENT ON COLUMN tenant.channel_hostnames.challenge_issued_at IS
    'When challenge_token was last issued or rotated. Null exactly when challenge_token is.';
