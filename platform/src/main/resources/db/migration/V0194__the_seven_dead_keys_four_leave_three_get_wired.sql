-- ADR 0030. Four dead configuration keys stop being declared; deleting any
-- stored rows for them in the same change, because ConfigurationKeyStartupValidator
-- refuses to boot over a row whose key_code has no code-owned declaration.
--
-- The finding: control-plane's Configuration & policy screen (V0193's own wave,
-- 2026-09-09) let an operator set any of ConfigurationKeys' fourteen declared
-- keys, but a repository-wide search for a live ConfigurationResolver consumer
-- turned up exactly seven. Of the other seven, three are wired to a real
-- consumer in this same change (pricing.quote_ttl_seconds,
-- ordering.cart_expiry_minutes, inventory.reservation_ttl_seconds — see
-- QuoteService, CartService, InventoryService). The remaining four are removed
-- outright, each because a second, better source of truth already exists and
-- makes the key a strictly worse duplicate:
--
--   ordering.approval_timeout_seconds   -> ordering.acceptance policy document
--                                           (OrderAcceptancePolicy.approvalTimeoutSeconds,
--                                           migrated off its own table by V0012)
--   notifications.quiet_hours_start_hour -> marketing.engagement_policies'
--                                           per-tenant quiet_hours_start/end
--                                           LocalTime columns (and
--                                           notification_preferences' own,
--                                           per-customer pair)
--   platform.default_locale             -> horecaos.notifications.telegram.group-locale,
--                                           the only locale default anything
--                                           reads, and narrowly Telegram-specific
--   integration.pos_sync_enabled        -> integration.pos_sync_schedules.enabled
--                                           (per binding) plus the binding's own
--                                           CATALOG_READ capability state — finer
--                                           grained than a scope-settable boolean
--                                           could ever express, since a tenant may
--                                           hold more than one POS binding
--
-- No production tenant has set any of these four today (this platform has not
-- reached production; V0193's screen shipped one day before this migration),
-- so the DELETE below is a defensive no-op in practice and a real one in any
-- environment — dev, test, a reviewer's own sandbox — where somebody already
-- clicked Save on one of them.
DELETE FROM tenant.configuration_values
 WHERE key_code IN (
    'ordering.approval_timeout_seconds',
    'notifications.quiet_hours_start_hour',
    'platform.default_locale',
    'integration.pos_sync_enabled'
 );
