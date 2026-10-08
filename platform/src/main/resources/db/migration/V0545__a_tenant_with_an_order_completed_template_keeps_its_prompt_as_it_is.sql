-- ADR 0159, Rollout: the preservation step for the rating prompt.
--
-- Today the ORDER_COMPLETED notification is created the moment OrderCompleted is published and is
-- sent at once, on whatever channel its template is active on. ADR 0159 puts that prompt under a
-- versioned policy document (`reviews.feedback_prompt`, ADR 0030) whose built-in default is Telegram
-- on, SMS / email / push off, 30 minutes late, inside 09:00-21:00. A tenant that never touched the
-- policy would therefore see its SMS prompt stop the day `feature.feedback_settings` is switched on.
--
-- So, before any tenant is switched on, every tenant that has an ACTIVE ORDER_COMPLETED template is
-- given an explicit tenant-scope policy version with one row per channel that template reaches:
-- enabled, no delay, give up after six hours (today's `horecaos.notifications.order-expiry`), and no
-- send window. The switch then changes nothing for them. A tenant with no such template has
-- nothing to preserve (an unactivated template sends nothing) and gets the built-in default.
--
-- Written straight into tenant.policies / tenant.policy_current the way V0012 migrated
-- ordering.acceptance, because a migration has no actor to hand PolicyAuthor. Only the channels the
-- policy vocabulary knows are carried (TELEGRAM, SMS, EMAIL, PUSH); a template on MESSAGING_APP has
-- no adapter and so never sent. A tenant that already has a feedback_prompt version is left alone.

WITH reached AS (
    SELECT t.tenant_id,
           jsonb_agg(
               jsonb_build_object(
                   'fulfillmentMode', '*',
                   'channel', t.channel,
                   'enabled', true,
                   'delayMinutes', 0,
                   'giveUpAfterHours', 6)
               ORDER BY t.channel) AS prompt_rows
      FROM (SELECT DISTINCT tenant_id, channel
              FROM notifications.templates
             WHERE template_key = 'ORDER_COMPLETED'
               AND status = 'ACTIVE'
               AND channel IN ('TELEGRAM', 'SMS', 'EMAIL', 'PUSH')) t
     GROUP BY t.tenant_id
),
documents AS (
    SELECT r.tenant_id,
           uuidv7() AS policy_id,
           jsonb_build_object(
               'sendWindow', NULL,
               'inlineWindowDays', 7,
               'rows', r.prompt_rows) AS document
      FROM reached r
     WHERE NOT EXISTS (
               SELECT 1 FROM tenant.policies existing
                WHERE existing.key_code = 'reviews.feedback_prompt'
                  AND existing.scope_type = 'TENANT'
                  AND existing.tenant_id = r.tenant_id)
),
inserted AS (
    INSERT INTO tenant.policies (
        id, key_code, scope_type, tenant_id, brand_id, location_id,
        version, status, document, document_hash, valid_from, created_by, approved_by)
    SELECT d.policy_id, 'reviews.feedback_prompt', 'TENANT', d.tenant_id, NULL, NULL,
           1, 'ACTIVE', d.document,
           encode(sha256(convert_to(d.document::text, 'UTF8')), 'hex'),
           now(), 'migration:V0545', 'migration:V0545'
      FROM documents d
    RETURNING id, tenant_id
)
INSERT INTO tenant.policy_current (
    key_code, scope_type, tenant_id, brand_id, location_id, policy_id, policy_version, activated_by)
SELECT 'reviews.feedback_prompt', 'TENANT', i.tenant_id, NULL, NULL, i.id, 1, 'migration:V0545'
  FROM inserted i;
