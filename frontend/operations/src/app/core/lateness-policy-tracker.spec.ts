import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from './api/operations-paths';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from './lateness-policy';
import { LATENESS_POLICY_MAX_AGE_MS, LatenessPolicyTracker } from './lateness-policy-tracker';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
const OTHER: LocationScope = { ...SCOPE, locationId: 'l2' };

function policyWithGrace(seconds: number): LatenessPolicy {
  const thresholds = {
    atRiskBeforeSeconds: 300,
    lateAfterSeconds: seconds,
    noPromiseFallbackSeconds: 2700,
  };
  return { delivery: thresholds, pickup: thresholds, dineIn: thresholds };
}

describe('LatenessPolicyTracker', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('answers the platform default until a real document has been read', () => {
    const tracker = new LatenessPolicyTracker({ read: vi.fn() });

    expect(tracker.policy('l1')).toBe(PLATFORM_DEFAULT_LATENESS_POLICY);
    expect(tracker.isLoaded('l1')).toBe(false);
  });

  it('holds a read for the max age and reads again after it', async () => {
    const read = vi
      .fn()
      .mockResolvedValueOnce(policyWithGrace(60))
      .mockResolvedValue(policyWithGrace(120));
    const tracker = new LatenessPolicyTracker({ read });

    await tracker.refresh(SCOPE);
    expect(tracker.policy('l1').delivery.lateAfterSeconds).toBe(60);
    vi.advanceTimersByTime(LATENESS_POLICY_MAX_AGE_MS - 1);
    await tracker.refresh(SCOPE);
    expect(read).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1);
    await tracker.refresh(SCOPE);

    expect(read).toHaveBeenCalledTimes(2);
    expect(tracker.policy('l1').delivery.lateAfterSeconds).toBe(120);
  });

  it('does not record a failed read as loaded: the very next refresh asks again', async () => {
    const read = vi.fn().mockResolvedValueOnce(null).mockResolvedValue(policyWithGrace(60));
    const tracker = new LatenessPolicyTracker({ read });

    await tracker.refresh(SCOPE);
    expect(tracker.isLoaded('l1')).toBe(false);
    expect(tracker.policy('l1')).toBe(PLATFORM_DEFAULT_LATENESS_POLICY);

    await tracker.refresh(SCOPE);

    expect(read).toHaveBeenCalledTimes(2);
    expect(tracker.isLoaded('l1')).toBe(true);
    expect(tracker.policy('l1').delivery.lateAfterSeconds).toBe(60);
  });

  it('keeps the last good policy when a later read fails, and asks again on the next refresh', async () => {
    const read = vi
      .fn()
      .mockResolvedValueOnce(policyWithGrace(60))
      .mockResolvedValueOnce(null)
      .mockResolvedValue(policyWithGrace(180));
    const tracker = new LatenessPolicyTracker({ read });
    await tracker.refresh(SCOPE);

    vi.advanceTimersByTime(LATENESS_POLICY_MAX_AGE_MS);
    await tracker.refresh(SCOPE);
    expect(tracker.policy('l1').delivery.lateAfterSeconds, 'the failure changed nothing').toBe(60);

    await tracker.refresh(SCOPE);
    expect(tracker.policy('l1').delivery.lateAfterSeconds).toBe(180);
  });

  it('keeps each branch apart, so a second branch is read on its own', async () => {
    const read = vi.fn(async (scope: LocationScope) =>
      policyWithGrace(scope.locationId === 'l1' ? 60 : 900),
    );
    const tracker = new LatenessPolicyTracker({ read });

    await Promise.all([tracker.refresh(SCOPE), tracker.refresh(OTHER)]);

    expect(tracker.policy('l1').delivery.lateAfterSeconds).toBe(60);
    expect(tracker.policy('l2').delivery.lateAfterSeconds).toBe(900);
    expect(tracker.isLoaded('l3')).toBe(false);
  });

  it('shares one read between concurrent refreshes of the same branch', async () => {
    let release: (policy: LatenessPolicy) => void = () => undefined;
    const read = vi.fn(
      () =>
        new Promise<LatenessPolicy>((resolve) => {
          release = resolve;
        }),
    );
    const tracker = new LatenessPolicyTracker({ read });

    const first = tracker.refresh(SCOPE);
    const second = tracker.refresh(SCOPE);
    release(policyWithGrace(60));
    await Promise.all([first, second]);

    expect(read).toHaveBeenCalledTimes(1);
  });
});
