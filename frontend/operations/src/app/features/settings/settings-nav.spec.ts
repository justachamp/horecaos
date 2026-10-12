import { describe, expect, it } from 'vitest';

import type { Capability } from '../../core/auth/session-capabilities';
import { visibleSettings } from './settings-nav';

describe('settings navigation behind a feature flag (ADR 0082)', () => {
  const paths = (isOn: (flag: string) => boolean) =>
    visibleSettings(isOn).flatMap((group) => group.items.map((item) => item.path));

  it('leaves a flagged page out while its flag is off', () => {
    expect(paths(() => false)).not.toContain('support-visits');
    expect(paths(() => false)).toContain('data-privacy');
  });

  it('shows it once the flag is on for the tenant', () => {
    expect(paths((flag) => flag === 'feature.support_visits')).toContain('support-visits');
  });

  it('lists the preset-product-comments screen (row 2.1b) unconditionally, next to the catalog settings', () => {
    expect(paths(() => false)).toContain('comment-presets');
  });

  it('lists the approval thresholds screen (row 9.4) unconditionally, under Selling', () => {
    expect(paths(() => false)).toContain('approvals');
    const group = visibleSettings(() => false).find((candidate) =>
      candidate.items.some((item) => item.path === 'approvals'),
    );
    expect(group?.label).toBe('settings.nav.group.selling');
  });
});

describe('settings navigation by what the operator holds (ADR 0162)', () => {
  const KIOSKS = 'channel-setup/kiosks';
  const flagOn = (flag: string): boolean => flag === 'feature.kiosk_devices';
  const paths = (held: readonly Capability[], isOn = flagOn) =>
    visibleSettings(isOn, (capability) => held.includes(capability)).flatMap((group) =>
      group.items.map((item) => item.path),
    );

  it('offers a tenant reader the tenant’s settings, and kiosks only when she can also pair them', () => {
    expect(paths(['TENANT_READ'])).toContain('brand');
    expect(paths(['TENANT_READ'])).not.toContain(KIOSKS);
    expect(paths(['TENANT_READ', 'KIOSK_DEVICE_MANAGE'])).toContain(KIOSKS);
    expect(paths(['TENANT_READ', 'KIOSK_DEVICE_MANAGE'])).toContain('brand');
  });

  it('offers a branch manager who pairs kiosks the kiosks entry and none of the tenant’s settings', () => {
    expect(paths(['KIOSK_DEVICE_MANAGE', 'IAM_GRANT_MANAGE'])).toEqual([KIOSKS]);
  });

  it('keeps the kiosks entry dark for a tenant reader while the flag is off', () => {
    expect(paths(['TENANT_READ', 'KIOSK_DEVICE_MANAGE'], () => false)).not.toContain(KIOSKS);
  });

  it('does not let a flag she cannot read hide the kiosks entry from a branch manager (ADR 0082)', () => {
    // Flags are read with tenant.read; without it every flag reads as off, which is not the same as the flag being off.
    expect(paths(['KIOSK_DEVICE_MANAGE'], () => false)).toEqual([KIOSKS]);
  });

  it('leaves no group behind that has nothing left to show', () => {
    const groups = visibleSettings(flagOn, (capability) => capability === 'KIOSK_DEVICE_MANAGE');
    expect(groups.map((group) => group.label)).toEqual(['settings.nav.group.selling']);
  });
});
