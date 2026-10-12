import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../../settings/configuration-api';
import { VisibilityPanel } from './visibility-panel';

const SCOPE = { tenantId: 'tenant-1', brandId: 'brand-1' };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Values {
  visibility: string;
  minimum: number;
  approval: boolean;
  flag: boolean;
}

describe('VisibilityPanel', () => {
  let fixture: ComponentFixture<VisibilityPanel>;
  let configuration: { resolution: ReturnType<typeof vi.fn>; setValue: ReturnType<typeof vi.fn> };

  async function render(
    values: Values = { visibility: 'NONE', minimum: 10, approval: true, flag: true },
    options: { target?: 'TENANT' | 'BRAND'; canWrite?: boolean; moderate?: boolean } = {},
  ): Promise<void> {
    configuration = {
      resolution: vi.fn(async (_tenant: string, code: string) => {
        switch (code) {
          case 'feedback.public_visibility':
            return { value: values.visibility, currentVersionAtScope: 2 };
          case 'feedback.public_minimum_reviews':
            return { value: values.minimum, currentVersionAtScope: null };
          case 'feedback.comments_require_approval':
            return { value: values.approval, currentVersionAtScope: 5 };
          default:
            return { value: values.flag, currentVersionAtScope: values.flag ? 1 : null };
        }
      }),
      setValue: vi.fn().mockResolvedValue({}),
    };
    const held: Capability[] = options.moderate ? ['REVIEW_MODERATE'] : [];
    await TestBed.configureTestingModule({
      imports: [VisibilityPanel],
      providers: [
        provideRouter([]),
        { provide: ConfigurationApi, useValue: configuration },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VisibilityPanel);
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

  function choose(value: string): void {
    const select = el<HTMLSelectElement>('visibility-level');
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  async function save(): Promise<void> {
    el('visibility-save').click();
    await flush();
    fixture.detectChanges();
  }

  it('reads the three settings at the level it edits and shows nothing public by default', async () => {
    await render();

    expect(el<HTMLSelectElement>('visibility-level').value).toBe('NONE');
    expect(el<HTMLInputElement>('visibility-minimum').value).toBe('10');
    expect(el<HTMLInputElement>('visibility-approval').checked).toBe(true);
    expect(configuration.resolution).toHaveBeenCalledWith(
      'tenant-1',
      'feedback.public_visibility',
      'TENANT',
      null,
    );
  });

  it('shows the level the setting has, not the first of the three', async () => {
    await render({ visibility: 'RATING_AND_COMMENTS', minimum: 3, approval: false, flag: true });

    expect(el<HTMLSelectElement>('visibility-level').value).toBe('RATING_AND_COMMENTS');
    expect(el<HTMLInputElement>('visibility-minimum').value).toBe('3');
    expect(el<HTMLInputElement>('visibility-approval').checked).toBe(false);
  });

  it('reads a brand’s own level when the brand is the target', async () => {
    await render(undefined, { target: 'BRAND' });

    expect(configuration.resolution).toHaveBeenCalledWith(
      'tenant-1',
      'feedback.public_visibility',
      'BRAND',
      'brand-1',
    );
  });

  it('writes only what changed, each key against its own version and with the reason', async () => {
    await render(undefined, { target: 'BRAND' });

    choose('RATING_AND_COMMENTS');
    type('visibility-reason', 'show approved comments');
    await save();

    expect(configuration.setValue).toHaveBeenCalledTimes(1);
    expect(configuration.setValue).toHaveBeenCalledWith('tenant-1', 'feedback.public_visibility', {
      scopeType: 'BRAND',
      brandId: 'brand-1',
      explicitNull: false,
      stringValue: 'RATING_AND_COMMENTS',
      expectedVersion: 2,
      reason: 'show approved comments',
    });
  });

  it('writes a first value with no version, and the tenant level with no brand', async () => {
    await render();

    type('visibility-minimum', '25');
    type('visibility-reason', 'a higher bar');
    await save();

    expect(configuration.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'feedback.public_minimum_reviews',
      expect.objectContaining({
        scopeType: 'TENANT',
        brandId: null,
        integerValue: 25,
        expectedVersion: null,
      }),
    );
  });

  it('writes the approval switch as a boolean', async () => {
    await render();

    el<HTMLInputElement>('visibility-approval').click();
    type('visibility-reason', 'we trust our customers');
    await save();

    expect(configuration.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'feedback.comments_require_approval',
      expect.objectContaining({ booleanValue: false, expectedVersion: 5 }),
    );
  });

  it('will not save with nothing changed, without a reason, or with a minimum outside 0 to 1000', async () => {
    await render();
    expect(el<HTMLButtonElement>('visibility-save').disabled).toBe(true);

    choose('RATING_ONLY');
    expect(el<HTMLButtonElement>('visibility-save').disabled).toBe(true);

    type('visibility-reason', 'x');
    expect(el<HTMLButtonElement>('visibility-save').disabled).toBe(false);

    type('visibility-minimum', '1001');
    expect(el<HTMLButtonElement>('visibility-save').disabled).toBe(true);
    expect(el('visibility-invalid').textContent).toContain('0 to 1000');

    type('visibility-minimum', '-1');
    expect(el<HTMLButtonElement>('visibility-save').disabled).toBe(true);
  });

  it('says the feature is off when the tenant has not switched it on, and switches it on', async () => {
    await render({ visibility: 'NONE', minimum: 10, approval: true, flag: false });

    expect(el('visibility-flag').textContent).toContain('nothing is public');
    el('visibility-flag-toggle').click();
    await flush();

    expect(configuration.setValue).toHaveBeenCalledWith(
      'tenant-1',
      'feature.feedback_settings',
      expect.objectContaining({ scopeType: 'TENANT', booleanValue: true }),
    );
  });

  it('links to the moderation queue only for someone who may moderate', async () => {
    await render(undefined, { moderate: false });
    expect(host().querySelector('a[href="/customers/reviews/moderation"]')).toBeNull();

    TestBed.resetTestingModule();
    await render(undefined, { moderate: true });
    expect(host().querySelector('a[href="/customers/reviews/moderation"]')).not.toBeNull();
  });

  it('reloads and says so when someone saved first', async () => {
    await render();
    choose('RATING_ONLY');
    type('visibility-reason', 'x');
    configuration.setValue.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, null, 'c'),
    );
    configuration.resolution.mockClear();

    await save();

    expect(el('visibility-error').textContent).toContain('Someone else saved first');
    expect(configuration.resolution).toHaveBeenCalled();
  });

  it('is read-only for someone who may not write', async () => {
    await render(undefined, { canWrite: false });

    expect(host().querySelector('[data-testid="visibility-save"]')).toBeNull();
    expect(host().querySelector('[data-testid="visibility-flag-toggle"]')).toBeNull();
    expect(el<HTMLSelectElement>('visibility-level').disabled).toBe(true);
  });
});

beforeEach(() => TestBed.resetTestingModule());
