import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { PaidHoldApi, PaidHoldPolicy } from './paid-hold-api';
import { PaidHoldCard } from './paid-hold-card';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function policy(overrides: Partial<PaidHoldPolicy> = {}): PaidHoldPolicy {
  return {
    schema: 1,
    mode: 'SHADOW',
    overdueAfterMinutes: 10,
    isDefault: true,
    winningScope: null,
    policyId: null,
    policyVersion: 0,
    versionAtScope: 0,
    levels: [
      { scopeType: 'LOCATION', authored: false },
      { scopeType: 'BRAND', authored: false },
      { scopeType: 'TENANT', authored: false },
      { scopeType: 'PLATFORM', authored: false },
    ],
    ...overrides,
  };
}

const PLATFORM_HOLD = policy({
  mode: 'HOLD_UNTIL_PAID',
  isDefault: false,
  winningScope: 'PLATFORM',
  policyId: 'p1',
  policyVersion: 1,
  levels: [
    { scopeType: 'LOCATION', authored: false },
    { scopeType: 'BRAND', authored: false },
    { scopeType: 'TENANT', authored: false },
    { scopeType: 'PLATFORM', authored: true },
  ],
});

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PaidHoldCard (ADR 0168 Decision 8)', () => {
  let fixture: ComponentFixture<PaidHoldCard>;

  async function render(
    api: Partial<PaidHoldApi>,
    options: { canManage?: boolean; locale?: 'en' | 'ru' | 'uz-Latn' } = {},
  ): Promise<void> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [PaidHoldCard],
      providers: [{ provide: PaidHoldApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale(options.locale ?? 'en');
    fixture = TestBed.createComponent(PaidHoldCard);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('canManage', options.canManage ?? true);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement | null {
    return host().querySelector(`[data-testid="${testId}"]`);
  }

  function type(testId: string, value: string): void {
    const input = byId(testId) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(testId: string, value: string): void {
    const select = byId(testId) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  async function openForm(): Promise<void> {
    const action = [...host().querySelectorAll('button')].find((button) =>
      /Override|Edit|Set value/i.test(button.textContent ?? ''),
    );
    expect(action).toBeDefined();
    action?.click();
    await flush();
    fixture.detectChanges();
  }

  it('says the platform is in trial when nothing was published: it counts and holds nothing', async () => {
    await render({ policy: () => Promise.resolve(policy()) });

    expect(host().textContent).toContain('Trial (count only)');
    expect(host().textContent).toContain('stuck after 10 min');
    expect(byId('paid-hold-trial')?.textContent).toContain('holds none');
  });

  it('shows a mode published at the platform level, and where it comes from', async () => {
    await render({ policy: () => Promise.resolve(PLATFORM_HOLD) });

    expect(host().textContent).toContain('Hold until paid');
    expect(byId('paid-hold-trial')).toBeNull();
  });

  it('shows the mode read-only to a manager without kitchen.paid-hold.manage', async () => {
    await render({ policy: () => Promise.resolve(PLATFORM_HOLD) }, { canManage: false });

    expect(byId('paid-hold-readonly')?.textContent).toContain('Hold until paid');
    expect(host().querySelector('q-inherited-field')).toBeNull();
  });

  it('pre-fills the form with the document in force and publishes it whole under the version read', async () => {
    const publish = vi.fn().mockResolvedValue(PLATFORM_HOLD);
    const read = vi
      .fn()
      .mockResolvedValueOnce(PLATFORM_HOLD) // the field
      .mockResolvedValueOnce(policy({ ...PLATFORM_HOLD, versionAtScope: 0 })); // the LOCATION level, opened
    await render({ policy: read, publish });
    await openForm();

    expect((byId('paid-hold-mode') as HTMLSelectElement).value).toBe('HOLD_UNTIL_PAID');
    expect((byId('paid-hold-overdue') as HTMLInputElement).value).toBe('10');

    choose('paid-hold-mode', 'OFF');
    type('paid-hold-overdue', '25');
    type('paid-hold-reason', 'this branch serves regulars on trust');
    (byId('paid-hold-save') as HTMLButtonElement).click();
    await flush();

    expect(publish).toHaveBeenCalledWith(
      SCOPE,
      'LOCATION',
      {
        mode: 'OFF',
        overdueAfterMinutes: 25,
        reason: 'this branch serves regulars on trust',
      },
      0,
    );
  });

  it('will not save minutes outside 1..1440, a decimal, or a missing reason', async () => {
    const publish = vi.fn();
    await render({ policy: () => Promise.resolve(policy()), publish });
    await openForm();

    type('paid-hold-reason', 'because');
    for (const bad of ['0', '1441', '2.5', '']) {
      type('paid-hold-overdue', bad);
      expect((byId('paid-hold-save') as HTMLButtonElement).disabled, bad).toBe(true);
    }
    expect(byId('paid-hold-invalid')).not.toBeNull();
    type('paid-hold-overdue', '15');
    expect((byId('paid-hold-save') as HTMLButtonElement).disabled).toBe(false);
    type('paid-hold-reason', '   ');
    expect((byId('paid-hold-save') as HTMLButtonElement).disabled).toBe(true);
    expect(publish).not.toHaveBeenCalled();
  });

  it('re-reads the document when another level is chosen and saves at that level', async () => {
    const publish = vi.fn().mockResolvedValue(PLATFORM_HOLD);
    const read = vi.fn().mockResolvedValue(policy({ versionAtScope: 3 }));
    await render({ policy: read, publish });
    await openForm();

    choose('paid-hold-level', 'TENANT');
    await flush();
    fixture.detectChanges();

    expect(read).toHaveBeenLastCalledWith(SCOPE, 'TENANT');
    type('paid-hold-reason', 'the whole company');
    (byId('paid-hold-save') as HTMLButtonElement).click();
    await flush();

    expect(publish).toHaveBeenCalledWith(
      SCOPE,
      'TENANT',
      { mode: 'SHADOW', overdueAfterMinutes: 10, reason: 'the whole company' },
      3,
    );
  });

  it('tells the manager someone else changed the policy, and offers no further save', async () => {
    const publish = vi.fn().mockRejectedValue(new ApiError('STALE_VERSION', 409, null, 'corr-1'));
    await render({ policy: () => Promise.resolve(policy()), publish });
    await openForm();
    type('paid-hold-reason', 'first');

    (byId('paid-hold-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('paid-hold-stale')).not.toBeNull();
    expect((byId('paid-hold-save') as HTMLButtonElement).disabled).toBe(true);
  });

  it('keeps the form open and shows the refusal when the platform refuses the document', async () => {
    const publish = vi.fn().mockRejectedValue(new ApiError('FORBIDDEN', 403, null, 'corr-2'));
    await render({ policy: () => Promise.resolve(policy()), publish });
    await openForm();
    type('paid-hold-reason', 'x');

    (byId('paid-hold-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(byId('paid-hold-save-error')).not.toBeNull();
    expect(byId('paid-hold-form')).not.toBeNull();
  });

  it('says the policy was saved, and re-reads what is now in force, once a publication lands', async () => {
    const publish = vi.fn().mockResolvedValue(PLATFORM_HOLD);
    const read = vi.fn().mockResolvedValue(policy());
    await render({ policy: read, publish });
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    await openForm();
    type('paid-hold-reason', 'x');

    (byId('paid-hold-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(saved).toHaveBeenCalledTimes(1);
    expect(byId('paid-hold-form')).toBeNull();
    expect(byId('paid-hold-saved')).not.toBeNull();
  });

  it('reads in Russian and Uzbek too', async () => {
    await render({ policy: () => Promise.resolve(PLATFORM_HOLD) }, { locale: 'ru' });
    expect(host().textContent).toContain('Удерживать до оплаты');

    await render({ policy: () => Promise.resolve(PLATFORM_HOLD) }, { locale: 'uz-Latn' });
    expect(host().textContent).toContain('Toʻlovgacha ushlab turish');
  });
});
