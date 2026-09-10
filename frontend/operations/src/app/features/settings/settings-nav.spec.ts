import { describe, expect, it } from 'vitest';

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
});
