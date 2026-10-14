-- ADR 0177 decision 2 (gap map row 10.3b). Five card-2 configuration keys stop being declared, and any
-- stored value for them goes in the same change, because ConfigurationKeyStartupValidator refuses to boot
-- over a tenant.configuration_values row whose key_code has no code-owned declaration (ADR 0030; the
-- precedent is V0194).
--
-- Each was registered by wave P46 so it could be authored before anything read it, and each is still read
-- by nothing in src/main (a search by constant and by code string, 2026-10-14). Each has a neighbour that
-- already owns the behaviour, which is why the key is removed rather than given a second reader:
--
--   ordering.business_day_start_hour        -> reporting.business_day_policies (row 10.10b, ADR 0043/0107)
--   ordering.average_order_minutes          -> the preparation bands of ADR 0036 (row 10.2c)
--   ordering.maximum_order_minutes          -> nothing; it would have been a fourth lateness severity that
--                                              orders.md 2.7 does not have (ADR 0150 decision 5)
--   ordering.vat_rate_percent               -> pricing.tax_profiles, dated by valid_from / valid_until
--                                              (row 4.8a, ADR 0018)
--   ordering.routing_poll_interval_minutes  -> the sourcing scheduler's deploy property and the dispatch
--                                              rules of ADR 0142 (row 3.8)
--
-- The delete is a no-op on an estate where nobody saved one of these fields (the page showed that four of
-- them were "not applied yet", and no environment has run in production), and a real one for a developer's
-- or a reviewer's database where somebody did. Audit facts that mention a key stay: they are append-only
-- evidence of what was once set, and name no declaration.
DELETE FROM tenant.configuration_values
 WHERE key_code IN (
    'ordering.business_day_start_hour',
    'ordering.average_order_minutes',
    'ordering.maximum_order_minutes',
    'ordering.vat_rate_percent',
    'ordering.routing_poll_interval_minutes'
 );
