-- Local development fixture only. application-local.yml is the sole profile
-- that discovers this directory; production's profile leaves it out entirely.
--
-- The rows below are deliberately additive and use fixed identifiers. They make
-- the unauthenticated storefront endpoints useful on a fresh laptop without
-- disguising this as production seed data. No DELETE or UPDATE appears here:
-- changing the fixture must not overwrite a developer's local work.
--
-- See docs/local-fixtures.md for the identifiers and manual API requests.

-- ---------------------------------------------------------------------------
-- Tenant, organisation hierarchy, and serviceability
-- ---------------------------------------------------------------------------

INSERT INTO tenant.tenants (
    id, slug, legal_name, display_name, default_currency, default_timezone, status
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    'qoida-local-cafe',
    'Qoida Local Cafe LLC',
    'Qoida Local Cafe',
    'UZS', 'Asia/Tashkent', 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.brands (
    id, tenant_id, code, slug, display_name, status
) VALUES (
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000001',
    'LOCAL_CAFE', 'qoida-local-cafe', 'Qoida Local Cafe', 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.locations (
    id, tenant_id, brand_id, code, slug, display_name, timezone, status,
    latitude, longitude, coordinate_source, address_line, district, city,
    landmark, contact_phone
) VALUES (
    '10000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'CENTRAL', 'central', 'Central kitchen', 'Asia/Tashkent', 'ACTIVE',
    41.311100, 69.240100, 'OPERATOR_PIN',
    '1 Demo Street', 'Shaykhontohur', 'Tashkent', 'Local fixture only', '+998712000000'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.customer_identity_policies (
    id, tenant_id, version, identity_mode, effective_from
) VALUES (
    '10000000-0000-0000-0000-000000000004',
    '10000000-0000-0000-0000-000000000001', 1, 'TENANT_SHARED', '2020-01-01T00:00:00Z'
) ON CONFLICT DO NOTHING;

-- ADR 0030 stores acceptance rules in the shared, scoped policy register.
-- This tenant-level rule makes fixture checkout auto-confirm without requiring
-- an operator approval flow.
INSERT INTO tenant.policies (
    id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
    document, document_hash, valid_from, created_by
) VALUES (
    '10000000-0000-0000-0000-000000000005',
    'ordering.acceptance', 'TENANT',
    '10000000-0000-0000-0000-000000000001', NULL, NULL, 1, 'ACTIVE',
    '{"mode":"AUTO_CONFIRM","approvalChannel":"NONE","approvalTimeoutSeconds":0,"timeoutAction":"AUTO_CONFIRM","rejectionReasonRequired":false,"notifyCustomerWhilePending":false}'::jsonb,
    encode(sha256('{"mode":"AUTO_CONFIRM","approvalChannel":"NONE","approvalTimeoutSeconds":0,"timeoutAction":"AUTO_CONFIRM","rejectionReasonRequired":false,"notifyCustomerWhilePending":false}'::bytea), 'hex'),
    '2020-01-01T00:00:00Z', 'local-fixture'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.policy_current (
    key_code, scope_type, tenant_id, brand_id, location_id, policy_id, policy_version, activated_by
) VALUES (
    'ordering.acceptance', 'TENANT',
    '10000000-0000-0000-0000-000000000001', NULL, NULL,
    '10000000-0000-0000-0000-000000000005', 1, 'local-fixture'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.sales_channels (
    id, tenant_id, code, system_type, display_name, status, guest_orders_allowed
) VALUES (
    '10000000-0000-0000-0000-000000000006',
    '10000000-0000-0000-0000-000000000001',
    'STOREFRONT', 'WEB', 'Local storefront', 'ACTIVE', true
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000006',
    '10000000-0000-0000-0000-000000000003', 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.channel_payment_methods (tenant_id, channel_id, payment_method_code, enabled)
VALUES (
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000006', 'CASH', true
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000006', 'PICKUP', true),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000006', 'DELIVERY', true)
ON CONFLICT DO NOTHING;

INSERT INTO tenant.service_schedules (
    id, tenant_id, brand_id, name, accepts_scheduled_orders
) VALUES (
    '10000000-0000-0000-0000-000000000007',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'Every day, local fixture', true
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.service_schedule_rules (schedule_id, sequence, day_of_week, opens_at, closes_at)
VALUES
    ('10000000-0000-0000-0000-000000000007', 1, 1, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 2, 2, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 3, 3, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 4, 4, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 5, 5, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 6, 6, '00:00', '23:59:59'),
    ('10000000-0000-0000-0000-000000000007', 7, 7, '00:00', '23:59:59')
ON CONFLICT DO NOTHING;

INSERT INTO tenant.location_service_bindings (
    tenant_id, brand_id, location_id, fulfillment_mode, schedule_id
) VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000003', 'PICKUP', '10000000-0000-0000-0000-000000000007'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000003', 'DELIVERY', '10000000-0000-0000-0000-000000000007')
ON CONFLICT DO NOTHING;

INSERT INTO tenant.location_service_state (
    location_id, tenant_id, brand_id, mode, max_concurrent_orders
) VALUES (
    '10000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002', 'FOLLOW_SCHEDULE', 50
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.preparation_bands (
    id, tenant_id, brand_id, location_id, starts_at, ends_at, duration_minutes
) VALUES (
    '10000000-0000-0000-0000-000000000008',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000003', '00:00', '23:59:59', 20
) ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Published menu, live offerings, price and availability
-- ---------------------------------------------------------------------------

INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
VALUES (
    '10000000-0000-0000-0000-000000000010',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'LOCAL_MENU', 'Local fixture menu', 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO catalog.categories (id, tenant_id, brand_id, catalog_id, code, sort_order, status)
VALUES (
    '10000000-0000-0000-0000-000000000011',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000010', 'MAINS', 10, 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
VALUES
    ('10000000-0000-0000-0000-000000000012', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PLOV', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000013', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'SOMSA', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000014', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'SHASHLIK', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO catalog.variants (
    id, tenant_id, brand_id, product_id, sku, unit_code, is_default, status
) VALUES
    ('10000000-0000-0000-0000-000000000015', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000012',
     'LOCAL-PLOV', 'PORTION', true, 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000016', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000013',
     'LOCAL-SOMSA', 'PIECE', true, 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000017', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000014',
     'LOCAL-SHASHLIK', 'PORTION', true, 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id, sort_order)
VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000012', 10),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000013', 20),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000010', '10000000-0000-0000-0000-000000000014', 30)
ON CONFLICT DO NOTHING;

INSERT INTO catalog.category_products (tenant_id, brand_id, category_id, product_id, sort_order)
VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000012', 10),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000013', 20),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     '10000000-0000-0000-0000-000000000011', '10000000-0000-0000-0000-000000000014', 30)
ON CONFLICT DO NOTHING;

INSERT INTO catalog.translations (
    tenant_id, brand_id, entity_type, entity_id, locale, name, description
) VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000011', 'uz', 'Asosiy taomlar', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'PRODUCT', '10000000-0000-0000-0000-000000000012', 'uz', 'Osh', 'Mahalliy demo osh'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'PRODUCT', '10000000-0000-0000-0000-000000000013', 'uz', 'Somsa', 'Mahalliy demo somsa'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'PRODUCT', '10000000-0000-0000-0000-000000000014', 'uz', 'Shashlik', 'Mahalliy demo shashlik')
ON CONFLICT DO NOTHING;

INSERT INTO catalog.location_offerings (
    id, tenant_id, brand_id, location_id, variant_id, status, fulfillment_modes
) VALUES
    ('10000000-0000-0000-0000-000000000018', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000015', 'AVAILABLE', 'DELIVERY,PICKUP'),
    ('10000000-0000-0000-0000-000000000019', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000016', 'AVAILABLE', 'DELIVERY,PICKUP'),
    ('10000000-0000-0000-0000-000000000020', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000017', 'UNAVAILABLE', 'DELIVERY,PICKUP')
ON CONFLICT DO NOTHING;

INSERT INTO catalog.publications (
    id, tenant_id, brand_id, catalog_id, channel, status, content_hash, validation_report,
    activated_at
) VALUES (
    '10000000-0000-0000-0000-000000000021',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000010', 'STOREFRONT', 'PUBLISHED',
    '0f35a1beb24f4d73066d020a65c1b7e373c4b2fa79b9fb2f327252eb855c939f', '{}'::jsonb,
    '2020-01-01T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO catalog.publication_items (
    publication_id, tenant_id, brand_id, entity_type, entity_id, entity_version, immutable_content_json
) VALUES
    ('10000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'CATEGORY', '10000000-0000-0000-0000-000000000011', 1,
     '{"code":"MAINS","names":{"uz":{"name":"Asosiy taomlar"}},"sortOrder":10,"productIds":["10000000-0000-0000-0000-000000000012","10000000-0000-0000-0000-000000000013","10000000-0000-0000-0000-000000000014"]}'::jsonb),
    ('10000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PRODUCT', '10000000-0000-0000-0000-000000000012', 1,
     '{"code":"PLOV","names":{"uz":{"name":"Osh","description":"Mahalliy demo osh"}},"mediaAssetIds":[],"variants":[{"variantId":"10000000-0000-0000-0000-000000000015","sku":"LOCAL-PLOV","unitCode":"PORTION","isDefault":true}],"modifierGroupIds":[]}'::jsonb),
    ('10000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PRODUCT', '10000000-0000-0000-0000-000000000013', 1,
     '{"code":"SOMSA","names":{"uz":{"name":"Somsa","description":"Mahalliy demo somsa"}},"mediaAssetIds":[],"variants":[{"variantId":"10000000-0000-0000-0000-000000000016","sku":"LOCAL-SOMSA","unitCode":"PIECE","isDefault":true}],"modifierGroupIds":[]}'::jsonb),
    ('10000000-0000-0000-0000-000000000021', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PRODUCT', '10000000-0000-0000-0000-000000000014', 1,
     '{"code":"SHASHLIK","names":{"uz":{"name":"Shashlik","description":"Mahalliy demo shashlik"}},"mediaAssetIds":[],"variants":[{"variantId":"10000000-0000-0000-0000-000000000017","sku":"LOCAL-SHASHLIK","unitCode":"PORTION","isDefault":true}],"modifierGroupIds":[]}'::jsonb)
ON CONFLICT DO NOTHING;

INSERT INTO pricing.price_books (
    id, tenant_id, brand_id, name, currency, status, valid_from, priority
) VALUES (
    '10000000-0000-0000-0000-000000000022',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'Local standard prices', 'UZS', 'ACTIVE', '2020-01-01T00:00:00Z', 100
) ON CONFLICT DO NOTHING;

INSERT INTO pricing.price_book_assignments (
    id, tenant_id, brand_id, price_book_id, scope_type, scope_id, valid_from, priority
) VALUES (
    '10000000-0000-0000-0000-000000000023',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000022', 'BRAND', NULL, '2020-01-01T00:00:00Z', 100
) ON CONFLICT DO NOTHING;

INSERT INTO pricing.prices (
    id, tenant_id, brand_id, price_book_id, priceable_type, priceable_id,
    amount_minor, valid_from
) VALUES
    ('10000000-0000-0000-0000-000000000024', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000022',
     'VARIANT', '10000000-0000-0000-0000-000000000015', 45000, '2020-01-01T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000025', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000022',
     'VARIANT', '10000000-0000-0000-0000-000000000016', 8000, '2020-01-01T00:00:00Z'),
    ('10000000-0000-0000-0000-000000000026', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000022',
     'VARIANT', '10000000-0000-0000-0000-000000000017', 30000, '2020-01-01T00:00:00Z')
ON CONFLICT DO NOTHING;

INSERT INTO pricing.tax_profiles (
    id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points, valid_from
) VALUES (
    '10000000-0000-0000-0000-000000000027',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002', 'UZ', 'INCLUSIVE', 1200, '2020-01-01T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO inventory.stock_items (
    id, tenant_id, brand_id, location_id, variant_id, tracking_mode, unit_code, status
) VALUES
    ('10000000-0000-0000-0000-000000000028', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000015', 'BINARY', 'PORTION', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000029', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000016', 'BINARY', 'PIECE', 'ACTIVE'),
    ('10000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003',
     '10000000-0000-0000-0000-000000000017', 'BINARY', 'PORTION', 'ACTIVE')
ON CONFLICT DO NOTHING;

INSERT INTO inventory.positions (
    stock_item_id, tenant_id, brand_id, location_id, binary_available
) VALUES
    ('10000000-0000-0000-0000-000000000028', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', true),
    ('10000000-0000-0000-0000-000000000029', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', true),
    ('10000000-0000-0000-0000-000000000030', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', false)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Fiscal location identity and one delivery zone for the public quote endpoint
-- ---------------------------------------------------------------------------

INSERT INTO tenant.legal_entities (
    id, tenant_id, code, legal_name, short_name, tin, vat_registered,
    vat_certificate_reference, tax_profile_id, registered_address, contact_phone, status
) VALUES (
    '10000000-0000-0000-0000-000000000031',
    '10000000-0000-0000-0000-000000000001', 'LOCAL_CAFE',
    'Qoida Local Cafe LLC', 'Qoida Local Cafe', '123456789', true,
    'local-fixture-vat-certificate', '10000000-0000-0000-0000-000000000027',
    '1 Demo Street, Tashkent', '+998712000000', 'ACTIVE'
) ON CONFLICT DO NOTHING;

INSERT INTO tenant.location_fiscal_assignments (
    id, tenant_id, brand_id, location_id, legal_entity_id,
    effective_from, approved_by, approval_reference
) VALUES (
    '10000000-0000-0000-0000-000000000032',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000031',
    '2020-01-01', 'local-fixture', 'local-fixture'
) ON CONFLICT DO NOTHING;

INSERT INTO fulfillment.delivery_tariffs (
    id, tenant_id, brand_id, code, name, status, is_brand_default
) VALUES (
    '10000000-0000-0000-0000-000000000033',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    'LOCAL_DELIVERY', 'Local delivery tariff', 'ACTIVE', true
) ON CONFLICT DO NOTHING;

INSERT INTO fulfillment.delivery_tariff_versions (
    id, tenant_id, tariff_id, version, status, currency, fee_source, distance_mode,
    max_distance_meters, min_fee_minor, max_fee_minor, created_by, activated_by, activated_at
) VALUES (
    '10000000-0000-0000-0000-000000000034',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000033', 1, 'ACTIVE', 'UZS', 'TARIFF', 'RADIUS',
    5000, 15000, 30000,
    '10000000-0000-0000-0000-000000000099',
    '10000000-0000-0000-0000-000000000099', '2020-01-01T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO fulfillment.delivery_tariff_bands (
    tenant_id, tariff_id, tariff_version, sequence, from_meters, to_meters, base_minor, per_km_minor
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000033', 1, 1, 0, 5000, 15000, 2500
) ON CONFLICT DO NOTHING;

INSERT INTO fulfillment.service_zones (
    id, tenant_id, brand_id, zone_role, code, display_name_ru, display_name_uz,
    display_name_en, status
) VALUES (
    '10000000-0000-0000-0000-000000000035',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002', 'DELIVERY', 'LOCAL_5KM',
    'Локальная зона 5 км', 'Mahalliy 5 km hudud', 'Local 5 km zone', 'ACTIVE'
) ON CONFLICT DO NOTHING;

WITH local_area AS (
    SELECT ST_Multi(ST_Buffer(
        ST_SetSRID(ST_MakePoint(69.240100, 41.311100), 4326)::geography, 5000
    )::geometry)::geography AS shape
)
INSERT INTO fulfillment.service_zone_versions (
    id, tenant_id, zone_id, zone_role, version, status, area, authoring_shape,
    origin_location_id, priority, area_sq_meters, currency, delivery_tariff_id,
    free_delivery_from_minor, min_basket_minor, created_by, activated_by, activated_at
)
SELECT
    '10000000-0000-0000-0000-000000000036',
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000035', 'DELIVERY', 1, 'ACTIVE', shape,
    '{"type":"CIRCLE","center":{"lat":41.311100,"lon":69.240100},"radiusMeters":5000}'::jsonb,
    '10000000-0000-0000-0000-000000000003', 100, ST_Area(shape), 'UZS',
    '10000000-0000-0000-0000-000000000033', NULL, 0,
    '10000000-0000-0000-0000-000000000099',
    '10000000-0000-0000-0000-000000000099', '2020-01-01T00:00:00Z'
FROM local_area
ON CONFLICT DO NOTHING;

INSERT INTO fulfillment.zone_location_bindings (
    tenant_id, brand_id, zone_id, location_id, valid_from
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000035',
    '10000000-0000-0000-0000-000000000003', '2020-01-01T00:00:00Z'
) ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------- support (V0094)
--
-- Enough published help for the storefront's two support screens to render
-- against something real, in the three languages this deployment serves. Two
-- categories, one of which has two entries, so the grouping is visibly a
-- grouping rather than a flat list that happens to look like one.

INSERT INTO support.faq_categories (id, tenant_id, brand_id, code, sort_order, status)
VALUES
    ('10000000-0000-0000-0000-000000000040', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'DELIVERY', 10, 'PUBLISHED'),
    ('10000000-0000-0000-0000-000000000041', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PAYMENT', 20, 'PUBLISHED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO support.faq_entries (id, tenant_id, brand_id, category_id, code, sort_order, status)
VALUES
    ('10000000-0000-0000-0000-000000000042', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000040',
     'DELIVERY_TIME', 10, 'PUBLISHED'),
    ('10000000-0000-0000-0000-000000000043', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000040',
     'DELIVERY_AREA', 20, 'PUBLISHED'),
    ('10000000-0000-0000-0000-000000000044', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000041',
     'PAYMENT_METHODS', 10, 'PUBLISHED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO support.faq_translations (tenant_id, brand_id, entity_type, entity_id, locale, title, body)
VALUES
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000040', 'uz', 'Yetkazib berish', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000040', 'ru', 'Доставка', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000040', 'en', 'Delivery', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000041', 'uz', 'To''lov', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000041', 'ru', 'Оплата', NULL),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'CATEGORY', '10000000-0000-0000-0000-000000000041', 'en', 'Payment', NULL),

    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000042', 'uz',
     'Buyurtma qancha vaqtda yetkaziladi?',
     'Odatda 30-45 daqiqa, filial va masofaga qarab.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000042', 'ru',
     'Сколько времени занимает доставка?',
     'Обычно 30-45 минут, в зависимости от филиала и расстояния.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000042', 'en',
     'How long does delivery take?',
     'Usually 30-45 minutes, depending on the branch and the distance.'),

    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000043', 'uz',
     'Qayerlarga yetkazib berasiz?',
     'Filial atrofidagi 5 km radiusdagi hududlarga.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000043', 'ru',
     'Куда вы доставляете?',
     'В радиусе 5 км вокруг филиала.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000043', 'en',
     'Where do you deliver?',
     'Within 5 km of the branch.'),

    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000044', 'uz',
     'Qanday to''lash mumkin?',
     'Hozircha kuryerga naqd pul orqali.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000044', 'ru',
     'Как можно оплатить?',
     'Пока только наличными курьеру при получении.'),
    ('10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002',
     'ENTRY', '10000000-0000-0000-0000-000000000044', 'en',
     'How can I pay?',
     'Cash to the courier on delivery, for now.')
ON CONFLICT (tenant_id, entity_type, entity_id, locale) DO NOTHING;

INSERT INTO support.social_links (id, tenant_id, brand_id, platform, url, sort_order, status)
VALUES
    ('10000000-0000-0000-0000-000000000045', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'TELEGRAM', 'https://t.me/qoida', 10, 'PUBLISHED'),
    ('10000000-0000-0000-0000-000000000046', '10000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000002', 'PHONE', 'tel:+998000000000', 20, 'PUBLISHED')
ON CONFLICT (id) DO NOTHING;
