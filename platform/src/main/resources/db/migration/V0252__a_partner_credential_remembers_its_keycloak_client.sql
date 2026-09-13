-- ADR 0106 (wave P35), gap-map row 10.8d: partner.api_clients becomes a real
-- Keycloak confidential client rather than a row a database operator has to
-- insert by hand.
--
-- `keycloak_client_ref` is Keycloak's own internal client id (a UUID string,
-- distinct from `client_id`, the OAuth client_credentials identifier the
-- column beside it already holds) -- needed for every later Admin API call
-- (regenerate-secret, disable) without a search-by-client_id round trip on
-- every rotation, the same shape `iam.device_principals.keycloak_client_internal_id`
-- already uses for ADR 0079's device principal.
ALTER TABLE partner.api_clients
    ADD COLUMN keycloak_client_ref varchar(255);

COMMENT ON COLUMN partner.api_clients.keycloak_client_ref IS
    'ADR 0106. Keycloak''s own internal client id, used for regenerate-secret and disable Admin API calls. Never the OAuth client_id (see the column beside it) and never a secret.';
