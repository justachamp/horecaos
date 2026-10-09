import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../../settings/configuration-api';
import { recoveryPolicy } from './feedback-fixtures.testing';
import { FeedbackSettingsApi, RecoveryPolicy } from './feedback-settings-api';
import { RecoveryPolicyPanel } from './recovery-policy-panel';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RecoveryPolicyPanel', () => {
  let fixture: ComponentFixture<RecoveryPolicyPanel>;
  let api: {
    recoveryPolicy: ReturnType<typeof vi.fn>;
    saveRecoveryPolicy: ReturnType<typeof vi.fn>;
  };
  let configuration: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  async function render(
    policy: RecoveryPolicy | (() => Promise<RecoveryPolicy>) = recoveryPolicy(),
    options: { target?: 'TENANT' | 'BRAND'; canWrite?: boolean; flagOn?: boolean } = {},
  ): Promise<void> {
    api = {
      recoveryPolicy: vi.fn(typeof policy === 'function' ? policy : async () => policy),
      saveRecoveryPolicy: vi.fn(async () =>
        recoveryPolicy({ isDefault: false, versionAtScope: 1 }),
      ),
    };
    configuration = {
      resolution: vi.fn(async () => ({
        value: options.flagOn ?? false,
        currentVersionAtScope: options.flagOn ? 4 : null,
      })),
      setValue: vi.fn().mockResolvedValue({}),
    };
    await TestBed.configureTestingModule({
      imports: [RecoveryPolicyPanel],
      providers: [
        { provide: FeedbackSettingsApi, useValue: api },
        { provide: ConfigurationApi, useValue: configuration },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RecoveryPolicyPanel);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('target', options.target ?? 'TENANT');
    fixture.componentRef.setInput('canWrite', options.canWrite ?? true);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function el<T extends HTMLElement>(testId: string): T {
    const found = host().querySelector(`[data-testid="${testId}"]`);
    expect(found, testId).not.toBeNull();
    return found as T;
  }

  function type(testId: string, value: string): void {
    const input = el<HTMLInputElement>(testId);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  async function save(): Promise<void> {
    el('recovery-save').click();
    await flush();
    fixture.detectChanges();
  }

  it('shows the default clocks and the threshold, and says nothing has been saved', async () => {
    await render();

    expect(el<HTMLSelectElement>('recovery-auto-open').value).toBe('2');
    expect(el<HTMLInputElement>('recovery-first-response').value).toBe('240');
    expect(el<HTMLInputElement>('recovery-resolution').value).toBe('4320');
    expect(el('recovery-provenance').textContent).toContain('built-in default applies');
  });

  it('offers never, and one to five stars, for the threshold', async () => {
    await render();

    const options = Array.from(el<HTMLSelectElement>('recovery-auto-open').options).map(
      (o) => o.value,
    );
    expect(options).toEqual(['0', '1', '2', '3', '4', '5']);
    expect(el<HTMLSelectElement>('recovery-auto-open').options[0].textContent).toContain('Never');
  });

  it('saves the new rules against the version authored at this level, with the reason', async () => {
    await render(recoveryPolicy({ versionAtScope: 3, isDefault: false, winningScope: 'TENANT' }));

    const select = el<HTMLSelectElement>('recovery-auto-open');
    select.value = '3';
    select.dispatchEvent(new Event('change'));
    type('recovery-first-response', '60');
    type('recovery-resolution', '1440');
    el<HTMLInputElement>('recovery-alert-open').click();
    type('recovery-reason', 'tighten for the pilot');
    await save();

    expect(api.saveRecoveryPolicy).toHaveBeenCalledWith(
      { kind: 'TENANT', tenantId: 'tenant-1' },
      {
        autoOpenMaxRating: 3,
        firstResponseMinutes: 60,
        resolutionMinutes: 1440,
        alertOnOpen: true,
        alertOnBreach: false,
        reason: 'tighten for the pilot',
      },
      3,
    );
    expect(el('recovery-saved').textContent).toContain('Saved.');
  });

  it('refuses a resolution shorter than the first answer, and the bounds', async () => {
    await render();
    type('recovery-reason', 'x');

    type('recovery-first-response', '600');
    type('recovery-resolution', '500');
    expect(el<HTMLButtonElement>('recovery-save').disabled).toBe(true);
    expect(el('recovery-invalid').textContent).toContain('not shorter than the first');

    type('recovery-first-response', '14');
    type('recovery-resolution', '1440');
    expect(el<HTMLButtonElement>('recovery-save').disabled).toBe(true);

    type('recovery-first-response', '15');
    type('recovery-resolution', '43201');
    expect(el<HTMLButtonElement>('recovery-save').disabled).toBe(true);

    type('recovery-resolution', '43200');
    expect(el<HTMLButtonElement>('recovery-save').disabled).toBe(false);
  });

  it('writes a brand’s own policy at the brand, not the tenant', async () => {
    await render(recoveryPolicy({ isDefault: false, winningScope: 'TENANT', versionAtScope: 0 }), {
      target: 'BRAND',
    });

    expect(el('recovery-provenance').textContent).toContain('follows the company');
    type('recovery-reason', 'this brand runs no worklist');
    await save();

    expect(api.saveRecoveryPolicy.mock.calls[0][0]).toEqual({ kind: 'BRAND', scope: SCOPE });
  });

  it('says the feature is off and switches it on with the version it read', async () => {
    await render(recoveryPolicy(), { flagOn: false });

    expect(el('recovery-flag').textContent).toContain('Off');
    expect(el('recovery-flag').textContent).toContain('no new case opens');
    configuration.resolution.mockResolvedValue({ value: true, currentVersionAtScope: 1 });
    el('recovery-flag-toggle').click();
    await flush();
    fixture.detectChanges();

    expect(configuration.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'feature.service_recovery',
      expect.objectContaining({
        scopeType: 'TENANT',
        booleanValue: true,
        explicitNull: false,
        expectedVersion: null,
      }),
    );
    expect(el('recovery-flag').textContent).toContain('On');
  });

  it('switches it off against the version that switched it on', async () => {
    await render(recoveryPolicy(), { flagOn: true });

    el('recovery-flag-toggle').click();
    await flush();

    expect(configuration.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'feature.service_recovery',
      expect.objectContaining({ booleanValue: false, expectedVersion: 4 }),
    );
  });

  it('reloads the policy and says so when someone saved first', async () => {
    await render();
    type('recovery-reason', 'x');
    api.saveRecoveryPolicy.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c'),
    );
    api.recoveryPolicy.mockClear();

    await save();

    expect(el('recovery-error').textContent).toContain('Someone else saved first');
    expect(api.recoveryPolicy).toHaveBeenCalled();
  });

  it('is read-only for someone who may not write: no save, no switch', async () => {
    await render(recoveryPolicy(), { canWrite: false });

    expect(host().querySelector('[data-testid="recovery-save"]')).toBeNull();
    expect(host().querySelector('[data-testid="recovery-flag-toggle"]')).toBeNull();
    expect(el<HTMLSelectElement>('recovery-auto-open').disabled).toBe(true);
  });
});

beforeEach(() => TestBed.resetTestingModule());
