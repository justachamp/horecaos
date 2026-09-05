import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import {
  AudienceDetail,
  AudiencePredicate,
  AudienceSummary,
  SegmentsApi,
} from './segments-api';
import { SegmentsPage } from './segments-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

const PREDICATE: AudiencePredicate = {
  type: 'RECENCY_DAYS',
  operator: 'AT_LEAST',
  numericLow: 14,
  numericHigh: null,
  dateLow: null,
  dateHigh: null,
  textValues: null,
  audienceId: null,
};

function summary(overrides: Partial<AudienceSummary> = {}): AudienceSummary {
  return {
    audienceId: 'audience-1',
    name: 'Lapsed regulars',
    description: 'Ordered before, quiet lately',
    status: 'READY',
    definitionVersion: 1,
    createdAt: '2026-08-01T08:00:00Z',
    updatedAt: '2026-08-20T08:00:00Z',
    lastReach: 120,
    lastEvaluatedAt: '2026-08-20T08:00:00Z',
    ...overrides,
  };
}

function detail(overrides: Partial<AudienceDetail> = {}): AudienceDetail {
  return {
    audienceId: 'audience-1',
    name: 'Lapsed regulars',
    description: 'Ordered before, quiet lately',
    status: 'READY',
    definitionVersion: 1,
    createdAt: '2026-08-01T08:00:00Z',
    updatedAt: '2026-08-20T08:00:00Z',
    predicates: [PREDICATE],
    ...overrides,
  };
}

class FakeCurrentBrand {
  readonly scope = signal<BrandScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('SegmentsPage', () => {
  let fixture: ComponentFixture<SegmentsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    detail: ReturnType<typeof vi.fn>;
    define: ReturnType<typeof vi.fn>;
    redefine: ReturnType<typeof vi.fn>;
    buildSnapshot: ReturnType<typeof vi.fn>;
  };
  let brand: FakeCurrentBrand;

  async function render(
    listResult: readonly AudienceSummary[] | (() => Promise<readonly AudienceSummary[]>),
    currentBrand: FakeCurrentBrand = new FakeCurrentBrand(),
  ): Promise<void> {
    brand = currentBrand;
    api = {
      list: vi.fn(typeof listResult === 'function' ? listResult : async () => listResult),
      detail: vi.fn().mockResolvedValue(detail()),
      define: vi.fn().mockResolvedValue('audience-new'),
      redefine: vi.fn().mockResolvedValue(2),
      buildSnapshot: vi.fn().mockResolvedValue({ snapshotId: 's1', candidates: 200, members: 150, excluded: 50 }),
    };
    await TestBed.configureTestingModule({
      imports: [SegmentsPage],
      providers: [
        provideRouter([]),
        { provide: SegmentsApi, useValue: api },
        { provide: CurrentBrand, useValue: brand },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(SegmentsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('lists a segment with its last-reach snapshot figure', async () => {
    await render([summary()]);
    const text = host().textContent ?? '';

    expect(text).toContain('Lapsed regulars');
    expect(text).toContain('120');
    expect(api.list).toHaveBeenCalledWith(SCOPE);
  });

  it('shows the honest empty state rather than a zero-row table pretending to be data', async () => {
    await render([]);
    expect(host().textContent).toContain('No segments yet.');
    // The empty state renders in the single cell reserved for it, not as a
    // fabricated row of zeros.
    expect(host().querySelectorAll('tbody tr td.empty')).toHaveLength(1);
  });

  it('shows the denied state when the operator covers no brand at all — the persona-unreachable failure mode', async () => {
    const denied = new FakeCurrentBrand();
    denied.scope.set(null);
    denied.denied.set(true);
    await render([], denied);

    expect(host().textContent).toContain('No location in scope');
    expect(api.list).not.toHaveBeenCalled();
  });

  it('surfaces a 403 mid-load as the denied state, not the generic error band', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
    });
    expect(host().textContent).toContain('No location in scope');
  });

  it('surfaces a load failure as an honest message with a retry, never a raw error code', async () => {
    await render(async () => {
      throw new ApiError(ApiErrorCode.NETWORK_UNREACHABLE, 0, null, 'corr-1');
    });
    const text = host().textContent ?? '';

    expect(text).not.toContain('NETWORK_UNREACHABLE');
    expect(text.toLowerCase()).not.toContain('undefined');
    expect(host().querySelector('.error-band')).not.toBeNull();

    // Retry re-issues the load.
    api.list.mockResolvedValueOnce([summary()]);
    (host().querySelector('.error-band button') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();
    expect(host().textContent).toContain('Lapsed regulars');
  });

  describe('the builder — create', () => {
    beforeEach(async () => {
      await render([summary()]);
      (host().querySelector('.header button.primary') as HTMLButtonElement).click();
      fixture.detectChanges();
    });

    function nameInput(): HTMLInputElement {
      return host().querySelector('.builder-panel input[type="text"]') as HTMLInputElement;
    }

    function saveButton(): HTMLButtonElement {
      return host().querySelector('.builder-panel .panel__actions .primary') as HTMLButtonElement;
    }

    function setInput(el: HTMLInputElement, value: string): void {
      el.value = value;
      el.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    it('never calls define while the name is blank — the default predicate row alone is not enough', async () => {
      // Only the predicate's numeric value is filled; the name is left blank.
      const numericInput = host().querySelector(
        '.predicate-row__value',
      ) as HTMLInputElement;
      setInput(numericInput, '30');

      expect(saveButton().disabled).toBe(true);
      saveButton().click();
      await flushMicrotasks();

      expect(api.define).not.toHaveBeenCalled();
    });

    it('never calls define while every predicate row is unworkable, even with a name', async () => {
      setInput(nameInput(), 'New regulars');
      // Leave the numeric value blank — canSave must gate on the row, not just the name.
      expect(saveButton().disabled).toBe(true);

      saveButton().click();
      await flushMicrotasks();
      expect(api.define).not.toHaveBeenCalled();
    });

    it('defines a new audience with exactly the typed name and predicate once the form is complete', async () => {
      setInput(nameInput(), 'New regulars');
      const numericInput = host().querySelector(
        '.predicate-row__value',
      ) as HTMLInputElement;
      setInput(numericInput, '30');

      expect(saveButton().disabled).toBe(false);
      saveButton().click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.define).toHaveBeenCalledWith(SCOPE, {
        name: 'New regulars',
        description: null,
        predicates: [
          {
            type: 'RECENCY_DAYS',
            operator: 'AT_LEAST',
            numericLow: 30,
            numericHigh: null,
            dateLow: null,
            dateHigh: null,
            textValues: null,
            audienceId: null,
          },
        ],
      });
      // The builder closes and the list reloads on success.
      expect(host().querySelector('.builder-panel')).toBeNull();
    });
  });

  describe('the builder — edit calls redefine, never define', () => {
    it('PUTs the edited predicate set through redefine(scope, audienceId, predicates), not a fresh define', async () => {
      await render([summary()]);
      (host().querySelector('.table .link') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.detail).toHaveBeenCalledWith(SCOPE, 'audience-1');

      const numericInput = host().querySelector(
        '.predicate-row__value',
      ) as HTMLInputElement;
      numericInput.value = '45';
      numericInput.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      (host().querySelector('.builder-panel .panel__actions .primary') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.redefine).toHaveBeenCalledWith(SCOPE, 'audience-1', [
        { ...PREDICATE, numericLow: 45 },
      ]);
      expect(api.define).not.toHaveBeenCalled();
    });

    it('reports a save failure honestly inside the builder rather than closing it silently', async () => {
      await render([summary()]);
      (host().querySelector('.table .link') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      api.redefine.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.VALIDATION_FAILED, 422, null, null),
      );
      (host().querySelector('.builder-panel .panel__actions .primary') as HTMLButtonElement).click();
      await flushMicrotasks();
      fixture.detectChanges();

      // Still open, with an honest message — not a raw code, not a blank panel.
      expect(host().querySelector('.builder-panel')).not.toBeNull();
      const message = host().querySelector('.builder-panel .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('VALIDATION_FAILED');
    });
  });

  describe('snapshot', () => {
    it('builds a snapshot with the fixed, machine-facing purpose and closes the panel on success', async () => {
      await render([summary()]);
      (host().querySelector('.actions .secondary') as HTMLButtonElement).click();
      fixture.detectChanges();

      (
        host().querySelector('.snapshot-panel .panel__actions .primary') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(api.buildSnapshot).toHaveBeenCalledWith(
        SCOPE,
        'audience-1',
        'SMS',
        'Operations console: segment snapshot from Customers 5.3',
      );
      // NOTE (found while writing this test, reported rather than fixed —
      // out of this wave's scope): `confirmSnapshot()` sets `snapshotResult`
      // and then `snapshottingId.set(null)` in the same synchronous
      // continuation, and the template's "Reached N customers" result line
      // lives *inside* the `@if (snapshottingId(); as audienceId)` block.
      // The panel that would show the result closes in the same tick the
      // result becomes available, so an operator never actually sees the
      // reach figure this action just computed — it renders successfully in
      // no build ever observed. This assertion documents the real, current
      // (buggy) behaviour rather than the intended one.
      expect(host().querySelector('.snapshot-panel')).toBeNull();
    });

    it('surfaces a snapshot failure honestly and leaves the panel open to retry', async () => {
      await render([summary()]);
      (host().querySelector('.actions .secondary') as HTMLButtonElement).click();
      fixture.detectChanges();

      api.buildSnapshot.mockRejectedValueOnce(
        new ApiError(ApiErrorCode.ENTITLEMENT_REQUIRED, 402, null, null),
      );
      (
        host().querySelector('.snapshot-panel .panel__actions .primary') as HTMLButtonElement
      ).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host().querySelector('.snapshot-panel')).not.toBeNull();
      const message = host().querySelector('.snapshot-panel .error-text')?.textContent ?? '';
      expect(message.length).toBeGreaterThan(0);
      expect(message).not.toContain('ENTITLEMENT_REQUIRED');
    });
  });
});
