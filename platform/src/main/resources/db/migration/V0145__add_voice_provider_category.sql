-- ADR 0064: voice joins the channels as a provider category.
--
-- The owner resolved ADR 0064's first-adapter open input on 2026-09-05: build
-- both a hosted SIP/PBX adapter and an Asterisk-class (self-hosted) adapter,
-- both behind the same provider-neutral core. Neither is a new taxonomy
-- question the way MARKETPLACE was (V0038) — a voice provider is an ordinary
-- ADR 0026 installation, just a category the CHECK constraints have not named
-- yet. Both constraints are restated in full per this repo's own rule: a CHECK
-- constraint is never narrowed to an ALTER that only adds a value, because the
-- dropped-and-recreated constraint has to carry every value that was already
-- legal or a live row on an existing category fails the rewrite.
ALTER TABLE integration.provider_environments
    DROP CONSTRAINT ck_provider_environment_category;
ALTER TABLE integration.provider_environments
    ADD CONSTRAINT ck_provider_environment_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE'));

ALTER TABLE integration.installations
    DROP CONSTRAINT ck_installation_category;
ALTER TABLE integration.installations
    ADD CONSTRAINT ck_installation_category CHECK (
        provider_category IN ('POS', 'PAYMENT', 'DELIVERY', 'MARKETPLACE',
                              'NOTIFICATION', 'GEOCODING', 'OTHER', 'VOICE'));
