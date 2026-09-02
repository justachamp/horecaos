import { describe, expect, it } from 'vitest';

import { CAPABILITY_AREAS, CAPABILITY_SENTENCES } from './capability-sentences';

/**
 * The exact set `uz.horecaos.platform.iam.api.TenantRoleCatalog.tenantVisible()`
 * unions across the eight tenant-visible `PlatformRole` bundles as of this
 * wave — pasted rather than fetched, so this check runs with no backend and no
 * network, the same trade-off `messages.en.ts`'s own completeness mechanism
 * makes at compile time. **If a sibling wave adds a capability to one of the
 * eight bundles, this list drifts and this test stops proving what its name
 * says** — the fix is to re-run the extraction (grep `PlatformRole.java` for
 * the bundle in question) and update both this array and
 * `capability-sentences.ts`, not to delete the test.
 */
const TENANT_VISIBLE_CAPABILITY_CODES = [
  'approval.decide',
  'approval.policy.manage',
  'audience.export',
  'audience.read',
  'audit.read',
  'brand.read',
  'brand.write',
  'campaign.approve',
  'campaign.author',
  'catalog.author',
  'catalog.publish',
  'catalog.read',
  'channel.manage',
  'channel.read',
  'commercial.override.approve',
  'commercial.plan.read',
  'commercial.subscription.manage',
  'commercial.usage.read',
  'conversation.flow.manage',
  'conversation.inbox.manage',
  'courier.adjustment.approve',
  'courier.adjustment.create',
  'courier.cash.confirm',
  'courier.duty.manage',
  'courier.engagement.manage',
  'courier.ledger.read',
  'courier.payout.authorise',
  'courier.position.read',
  'courier.ratecard.manage',
  'courier.registration.verify',
  'courier.settlement.close',
  'courier.shift.approve',
  'customer.import',
  'customer.manage',
  'customer.pii.reveal',
  'customer.read',
  'delivery.cost.read',
  'delivery.fee.evidence.read',
  'delivery.manual_assign',
  'delivery.plan.read',
  'delivery.tariff.activate',
  'delivery.tariff.manage',
  'delivery.zone.activate',
  'delivery.zone.manage',
  'delivery.zone.read',
  'dinein.floorplan.manage',
  'dinein.qr.rotate',
  'dinein.session.force_close',
  'dinein.session.manage',
  'dinein.session.read',
  'fiscal.document.read',
  'fiscal.document.resolve',
  'iam.grant.manage',
  'integration.binding.activate',
  'integration.failure.read',
  'integration.failure.retry',
  'integration.installation.manage',
  'integration.telegram-link.issue',
  'integration.telegram-staff-link.issue',
  'inventory.adjust',
  'inventory.read',
  'kitchen.station.manage',
  'kitchen.ticket.advance',
  'kitchen.ticket.read',
  'kitchen.ticket.recall',
  'kitchen.ticket.release',
  'kitchen.ticket.release.override',
  'legal-entity.manage',
  'legal-entity.read',
  'location.read',
  'location.service-state.change',
  'location.write',
  'loyalty.adjust',
  'loyalty.policy.manage',
  'loyalty.read',
  'marketplace.availability.push',
  'marketplace.handover.bypass',
  'marketplace.liveness.read',
  'marketplace.menu.push',
  'marketplace.order.create.manual',
  'media.read',
  'media.upload',
  'notification.read',
  'notification.retry',
  'notification.template.activate',
  'notification.template.author',
  'offering.manage',
  'order.acceptance-policy.manage',
  'order.advance',
  'order.amend',
  'order.approve',
  'order.cancel',
  'order.outcome-reason.manage',
  'order.read',
  'order.state.override',
  'partner.invoice.manage',
  'payment.attempt.resolve',
  'payment.merchant-binding.manage',
  'payment.read',
  'pos.export.read',
  'pos.export.resolve',
  'pos.sync.apply',
  'pos.sync.execute',
  'pos.sync.read',
  'pricing.activate',
  'pricing.author',
  'pricing.read',
  'recovery.case.manage',
  'recovery.remedy.approve',
  'refund.approve',
  'refund.execute',
  'refund.request',
  'reporting.read',
  'reservation.manage',
  'reservation.read',
  'serviceability.manage',
  'shipment.cancel',
  'suppression.manage',
  'tenant.onboarding.manage',
  'tenant.read',
  'tenant.write',
] as const;

describe('CAPABILITY_SENTENCES', () => {
  it('has a build-time-equivalent entry for every capability a tenant-visible job can carry', () => {
    const missing = TENANT_VISIBLE_CAPABILITY_CODES.filter(
      (code) => !(code in CAPABILITY_SENTENCES),
    );
    expect(missing).toEqual([]);
  });

  it('fills in all three locales, non-blank, for every entry', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      expect(sentence.ru.trim(), `${code}.ru`).not.toBe('');
      expect(sentence.uz.trim(), `${code}.uz`).not.toBe('');
      expect(sentence.en.trim(), `${code}.en`).not.toBe('');
      expect(sentence.area.trim(), `${code}.area`).not.toBe('');
    }
  });

  it('never carries a dotted code as a sentence', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      expect(sentence.ru, code).not.toBe(code);
      expect(sentence.uz, code).not.toBe(code);
      expect(sentence.en, code).not.toBe(code);
    }
  });

  it('every entry names an area with all three locales filled in', () => {
    for (const [code, sentence] of Object.entries(CAPABILITY_SENTENCES)) {
      const area = CAPABILITY_AREAS[sentence.area];
      expect(area, `${code}'s area "${sentence.area}"`).toBeDefined();
      expect(area.ru.trim()).not.toBe('');
      expect(area.uz.trim()).not.toBe('');
      expect(area.en.trim()).not.toBe('');
    }
  });
});
