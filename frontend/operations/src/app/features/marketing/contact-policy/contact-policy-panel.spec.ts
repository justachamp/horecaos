import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { REFUSAL_EXPLANATIONS } from '../refusal-explainer';
import {
  ContactPolicyApi,
  ContactPolicyOverrideView,
  ContactPolicyView,
  PlatformBoundsView,
} from './contact-policy-api';
import { ContactPolicyPanel, ceilingFor, clock, overrideProblem } from './contact-policy-panel';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const BOUNDS: PlatformBoundsView = {
  quietHoursStartNoLaterThan: '21:00:00',
  quietHoursEndNoEarlierThan: '10:00:00',
  dailyCapCeiling: 3,
  weeklyCapCeiling: 3,
  rolling7DayCapCeiling: 3,
  rolling30DayCapCeiling: 8,
};

function override(overrides: Partial<ContactPolicyOverrideView> = {}): ContactPolicyOverrideView {
  return {
    channel: 'SMS',
    campaignPurpose: 'MARKETING_PROMOTIONS',
    period: 'WEEKLY',
    capCount: 1,
    quietHoursStart: null,
    quietHoursEnd: null,
    statedReason: 'Guests complained about texts',
    updatedBy: 'actor-1',
    version: 3,
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  };
}

const FORM = {
  capCount: 1 as number | null,
  quietStart: '',
  quietEnd: '',
  period: 'WEEKLY' as const,
  reason: 'Because',
  purpose: 'MARKETING_PROMOTIONS',
};

describe('overrideProblem: tighten-only, with the platform’s number in the sentence', () => {
  it('accepts a cap at or under the ceiling and quiet hours that start earlier and end later', () => {
    expect(overrideProblem(FORM, BOUNDS)).toBeNull();
    expect(overrideProblem({ ...FORM, capCount: 3 }, BOUNDS)).toBeNull();
    expect(
      overrideProblem({ ...FORM, capCount: null, quietStart: '20:00', quietEnd: '11:00' }, BOUNDS),
    ).toBeNull();
    expect(
      overrideProblem({ ...FORM, capCount: null, quietStart: '21:00', quietEnd: '10:00' }, BOUNDS),
    ).toBeNull();
  });

  it('refuses a cap above the platform ceiling, naming both numbers', () => {
    const problem = overrideProblem({ ...FORM, capCount: 4 }, BOUNDS);
    expect(problem?.key).toBe('marketing.contactPolicy.problem.capLoosened');
    expect(problem?.values).toEqual({ cap: 4, ceiling: 3 });
  });

  it('reads the ceiling per period: thirty days allows eight', () => {
    expect(ceilingFor('ROLLING_30D', BOUNDS)).toBe(8);
    expect(overrideProblem({ ...FORM, period: 'ROLLING_30D', capCount: 8 }, BOUNDS)).toBeNull();
    expect(
      overrideProblem({ ...FORM, period: 'ROLLING_30D', capCount: 9 }, BOUNDS)?.values,
    ).toEqual({ cap: 9, ceiling: 8 });
  });

  it('refuses quiet hours that start later or end earlier than the platform’s', () => {
    expect(
      overrideProblem({ ...FORM, capCount: null, quietStart: '22:00', quietEnd: '11:00' }, BOUNDS)
        ?.key,
    ).toBe('marketing.contactPolicy.problem.quietStartLoosened');
    expect(
      overrideProblem({ ...FORM, capCount: null, quietStart: '20:00', quietEnd: '09:00' }, BOUNDS)
        ?.key,
    ).toBe('marketing.contactPolicy.problem.quietEndLoosened');
  });

  it('wants a quiet window to have both ends, a rule to say something, and a reason to be given', () => {
    expect(
      overrideProblem({ ...FORM, capCount: null, quietStart: '20:00', quietEnd: '' }, BOUNDS)?.key,
    ).toBe('marketing.contactPolicy.problem.quietPair');
    expect(overrideProblem({ ...FORM, capCount: null }, BOUNDS)?.key).toBe(
      'marketing.contactPolicy.problem.empty',
    );
    expect(overrideProblem({ ...FORM, reason: '  ' }, BOUNDS)?.key).toBe(
      'marketing.contactPolicy.problem.reason',
    );
    expect(overrideProblem({ ...FORM, purpose: '' }, BOUNDS)?.key).toBe(
      'marketing.contactPolicy.problem.purpose',
    );
    expect(overrideProblem({ ...FORM, capCount: -1 }, BOUNDS)?.key).toBe(
      'marketing.contactPolicy.problem.capNegative',
    );
  });

  it('reads a LocalTime with or without seconds the same way', () => {
    expect(clock('21:00:00')).toBe('21:00');
    expect(clock('21:00')).toBe('21:00');
  });
});

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ContactPolicyPanel', () => {
  let fixture: ComponentFixture<ContactPolicyPanel>;
  let host: HTMLElement;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  async function render(
    overrides: readonly ContactPolicyOverrideView[] = [],
    options: { readFails?: ApiError } = {},
  ): Promise<void> {
    const view: ContactPolicyView = { platform: BOUNDS, overrides };
    api = {
      read: options.readFails
        ? vi.fn().mockRejectedValue(options.readFails)
        : vi.fn().mockResolvedValue(view),
      defaults: vi.fn().mockResolvedValue({
        channelPriorityOrder: ['ORDER_UPDATES', 'MARKETING_PROMOTIONS'],
        inAppShowCapPerDay: 3,
        controlGroupPercentDefault: 10,
      }),
      set: vi.fn().mockResolvedValue(override()),
      remove: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [ContactPolicyPanel],
      providers: [
        { provide: ContactPolicyApi, useValue: api },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ContactPolicyPanel);
    host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function type(testId: string, value: string): void {
    const input = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(testId: string, value: string): void {
    const select = host.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('shows the platform’s bounds beside the brand’s own rules', async () => {
    await render([override()]);

    const bounds = host.querySelector('[data-testid="policy-bounds"]')!.textContent!;
    expect(bounds).toContain('21:00');
    expect(bounds).toContain('10:00');
    expect(bounds).toContain('8');
    const row = host.querySelector('[data-testid="policy-override-row"]')!.textContent!;
    expect(row).toContain('SMS');
    expect(row).toContain('Guests complained about texts');
  });

  it('says plainly when the brand has set nothing tighter', async () => {
    await render([]);

    expect(host.querySelector('[data-testid="policy-overrides-empty"]')).not.toBeNull();
  });

  it('shows the denied state when the policy cannot be read', async () => {
    await render([], {
      readFails: new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
    });

    expect(host.querySelector('[data-testid="policy-denied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="policy-create"]')).toBeNull();
  });

  it('shows the ADR 0030 values scenarios read, as read-only facts', async () => {
    await render([]);

    const defaults = host.querySelector('[data-testid="policy-defaults"]')!.textContent!;
    expect(defaults).toContain('ORDER_UPDATES › MARKETING_PROMOTIONS');
    expect(defaults).toContain('10%');
    expect(host.querySelectorAll('[data-testid="policy-defaults"] input')).toHaveLength(0);
  });

  describe('why a guest is blocked', () => {
    it('lists every reason the decision log can hold, in words, with whether it ends the run', async () => {
      await render([]);

      const reasons = [...host.querySelectorAll('[data-testid="policy-explainer-reason"]')];
      expect(reasons.map((r) => r.getAttribute('data-reason'))).toEqual(
        REFUSAL_EXPLANATIONS.map((e) => e.reason),
      );
      const text = host.querySelector('[data-testid="policy-explainer"]')!.textContent!;
      expect(text).toContain('Ends this guest’s run');
      expect(text).toContain('Holds the step and asks again later; never dropped');
    });

    it('marks the cap as the one reason this brand’s own policy can be behind, and says quiet hours never refuse', async () => {
      await render([]);

      const withPolicy = [
        ...host.querySelectorAll('[data-testid="policy-explainer-reason"]'),
      ].filter((r) => r.textContent!.includes('contact policy can be behind this'));
      expect(withPolicy.map((r) => r.getAttribute('data-reason'))).toEqual([
        'FREQUENCY_CAP_REACHED',
      ]);
      expect(host.querySelector('[data-testid="policy-explainer"]')!.textContent).toContain(
        'Quiet hours never refuse',
      );
    });
  });

  describe('setting a rule', () => {
    it('creates an override without a version: it is a new rule, so an existing one is refused, not overwritten', async () => {
      await render([]);
      (host.querySelector('[data-testid="policy-create"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      choose('policy-form-channel', 'SMS');
      choose('policy-form-period', 'WEEKLY');
      type('policy-form-cap', '1');
      type('policy-form-reason', 'Guests complained');
      (host.querySelector('[data-testid="policy-form-submit"]') as HTMLButtonElement).click();
      await flush();

      expect(api['set']).toHaveBeenCalledTimes(1);
      const [scope, request, version] = api['set'].mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(request).toEqual({
        channel: 'SMS',
        campaignPurpose: 'MARKETING_PROMOTIONS',
        period: 'WEEKLY',
        capCount: 1,
        quietHoursStart: null,
        quietHoursEnd: null,
        statedReason: 'Guests complained',
      });
      expect(version).toBeUndefined();
    });

    it('replaces an override at the version it read, and cannot change which rule it is', async () => {
      await render([override({ version: 3 })]);
      (host.querySelector('[data-testid="policy-replace"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(
        (host.querySelector('[data-testid="policy-form-channel"]') as HTMLSelectElement).disabled,
      ).toBe(true);
      expect(
        (host.querySelector('[data-testid="policy-form-period"]') as HTMLSelectElement).disabled,
      ).toBe(true);
      type('policy-form-cap', '2');
      type('policy-form-reason', 'Relaxed after review');
      (host.querySelector('[data-testid="policy-form-submit"]') as HTMLButtonElement).click();
      await flush();

      expect(api['set'].mock.calls[0][1].capCount).toBe(2);
      expect(api['set'].mock.calls[0][2]).toBe(3);
    });

    it('opens a replacement on the rule it replaces: its channel and period, not the first in each list', async () => {
      await render([override({ channel: 'EMAIL', period: 'ROLLING_30D', capCount: 5 })]);
      (host.querySelector('[data-testid="policy-replace"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(
        (host.querySelector('[data-testid="policy-form-channel"]') as HTMLSelectElement).value,
      ).toBe('EMAIL');
      expect(
        (host.querySelector('[data-testid="policy-form-period"]') as HTMLSelectElement).value,
      ).toBe('ROLLING_30D');
      expect(
        (host.querySelector('[data-testid="policy-form-cap"]') as HTMLInputElement).value,
      ).toBe('5');
    });

    it('refuses to send a loosening, naming the platform’s number, before the server is asked', async () => {
      await render([]);
      (host.querySelector('[data-testid="policy-create"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      choose('policy-form-period', 'WEEKLY');
      type('policy-form-cap', '5');
      type('policy-form-reason', 'More!');

      expect(host.querySelector('[data-testid="policy-form-problem"]')!.textContent).toContain(
        'exceeds the platform’s 3',
      );
      const submit = host.querySelector('[data-testid="policy-form-submit"]') as HTMLButtonElement;
      expect(submit.disabled).toBe(true);
      submit.click();
      expect(api['set']).not.toHaveBeenCalled();
    });

    it('shows the server’s own refusal in the form and keeps it open', async () => {
      await render([]);
      api['set'].mockRejectedValue(
        new ApiError(
          ApiErrorCode.RESOURCE_CONFLICT,
          409,
          {
            status: 409,
            detail: 'An override already exists for this channel, purpose and period',
          } as never,
          null,
        ),
      );
      (host.querySelector('[data-testid="policy-create"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      type('policy-form-cap', '1');
      type('policy-form-reason', 'Because');
      (host.querySelector('[data-testid="policy-form-submit"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="policy-form-error"]')!.textContent).toContain(
        'already exists',
      );
      expect(host.querySelector('q-drawer')).not.toBeNull();
    });
  });

  describe('removing a rule', () => {
    it('asks why, because removing a rule is as attributable as adding one', async () => {
      await render([override()]);
      (host.querySelector('[data-testid="policy-remove"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      const confirm = host.querySelector(
        '[data-testid="policy-remove-confirm"]',
      ) as HTMLButtonElement;
      expect(confirm.disabled).toBe(true);
      type('policy-remove-reason', 'Policy reviewed');
      expect(confirm.disabled).toBe(false);
      confirm.click();
      await flush();

      expect(api['remove']).toHaveBeenCalledTimes(1);
      const [scope, target, reason] = api['remove'].mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(target).toMatchObject({
        channel: 'SMS',
        campaignPurpose: 'MARKETING_PROMOTIONS',
        period: 'WEEKLY',
      });
      expect(reason).toBe('Policy reviewed');
      // Re-read, so the table shows what the server now holds.
      expect(api['read']).toHaveBeenCalledTimes(2);
    });

    it('shows a refused removal without losing the table', async () => {
      await render([override()]);
      api['remove'].mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null),
      );
      (host.querySelector('[data-testid="policy-remove"]') as HTMLButtonElement).click();
      fixture.detectChanges();
      type('policy-remove-reason', 'Reviewed');
      (host.querySelector('[data-testid="policy-remove-confirm"]') as HTMLButtonElement).click();
      await flush();
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="policy-action-error"]')).not.toBeNull();
      expect(host.querySelectorAll('[data-testid="policy-override-row"]')).toHaveLength(1);
    });
  });
});
