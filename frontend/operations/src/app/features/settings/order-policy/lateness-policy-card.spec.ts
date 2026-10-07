import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { LatenessEditorView, LatenessPolicyEditorApi } from './lateness-policy-editor-api';
import { LatenessPolicyCard } from './lateness-policy-card';

const TENANT_ID = 'tenant-1';
const BRAND_ID = 'brand-1';
const LOCATION_ID = 'location-1';

/** Nothing authored anywhere: no mode owns a window, the platform's five minutes is the default. */
const PLATFORM_DEFAULT: LatenessEditorView = {
  delivery: mode(null, 300, 0, null, 2700),
  pickup: mode(null, 300, 0, null, 2700),
  dineIn: mode(null, 300, 0, null, 2700),
  atRiskDefault: { seconds: 300, source: 'PLATFORM_DEFAULT' },
  noPromiseDefault: { seconds: 2700, source: 'PLATFORM_DEFAULT' },
  isPlatformDefault: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  currentVersionAtScope: 0,
  inspectedLevels: [
    { scopeType: 'BRAND', outcome: 'NOT_SET' },
    { scopeType: 'TENANT', outcome: 'NOT_SET' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
};

/** The tenant's document, inherited by the brand: delivery 10 min, pickup 2 min, dine-in unset. */
const INHERITED_FROM_TENANT: LatenessEditorView = {
  delivery: mode(600, 600, 60, 3600),
  pickup: mode(120, 120, 0, 1800),
  dineIn: mode(null, 720, 30, 1200),
  atRiskDefault: { seconds: 720, source: 'SCALAR' },
  noPromiseDefault: { seconds: 1500, source: 'SCALAR' },
  isPlatformDefault: false,
  winningScope: 'TENANT',
  policyId: 'policy-tenant',
  policyVersion: 3,
  currentVersionAtScope: 0,
  inspectedLevels: [
    { scopeType: 'BRAND', outcome: 'NOT_SET' },
    { scopeType: 'TENANT', outcome: 'VALUE' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
};

/** As the tenant's document, but pickup says nothing about its fallback: the scalar's 25 minutes applies. */
const BLANK_FALLBACK_FROM_TENANT: LatenessEditorView = {
  ...INHERITED_FROM_TENANT,
  pickup: mode(120, 120, 0, null, 1500),
};

/** The brand's own document, version 2. */
const SET_AT_BRAND: LatenessEditorView = {
  ...INHERITED_FROM_TENANT,
  winningScope: 'BRAND',
  policyId: 'policy-brand',
  policyVersion: 2,
  currentVersionAtScope: 2,
  inspectedLevels: [
    { scopeType: 'BRAND', outcome: 'VALUE' },
    { scopeType: 'TENANT', outcome: 'VALUE' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
};

function mode(
  atRiskBeforeSeconds: number | null,
  effectiveAtRiskBeforeSeconds: number,
  lateAfterSeconds: number,
  noPromiseFallbackSeconds: number | null,
  effectiveNoPromiseFallbackSeconds: number | null = noPromiseFallbackSeconds,
) {
  return {
    atRiskBeforeSeconds,
    effectiveAtRiskBeforeSeconds,
    lateAfterSeconds,
    noPromiseFallbackSeconds,
    effectiveNoPromiseFallbackSeconds: effectiveNoPromiseFallbackSeconds ?? 2700,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('LatenessPolicyCard', () => {
  let api: { get: ReturnType<typeof vi.fn>; publish: ReturnType<typeof vi.fn> };
  let fixture: ComponentFixture<LatenessPolicyCard>;

  async function render(
    view: LatenessEditorView,
    scopeType: 'TENANT' | 'BRAND' | 'LOCATION' = 'BRAND',
  ): Promise<void> {
    api.get.mockResolvedValue(view);
    fixture = TestBed.createComponent(LatenessPolicyCard);
    fixture.componentRef.setInput('tenantId', TENANT_ID);
    fixture.componentRef.setInput('scopeType', scopeType);
    fixture.componentRef.setInput('brandId', BRAND_ID);
    fixture.componentRef.setInput('locationId', scopeType === 'LOCATION' ? LOCATION_ID : null);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function input(id: string): HTMLInputElement {
    const found = el().querySelector<HTMLInputElement>(`#${id}`);
    if (!found) {
      throw new Error(`no input #${id}`);
    }
    return found;
  }

  function type(id: string, value: string): void {
    const box = input(id);
    box.value = value;
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement {
    const found = [...el().querySelectorAll<HTMLButtonElement>('button')].find((candidate) =>
      candidate.textContent?.includes(label),
    );
    if (!found) {
      throw new Error(`no button "${label}"`);
    }
    return found;
  }

  async function openForm(): Promise<void> {
    button('Override here').click();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    api = { get: vi.fn(), publish: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [LatenessPolicyCard],
      providers: [{ provide: LatenessPolicyEditorApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
  });

  // ---------------------------------------------------------------- reading

  it('reads the document at the scope bar’s own level and shows one row per fulfilment mode', async () => {
    await render(INHERITED_FROM_TENANT);

    expect(api.get).toHaveBeenCalledWith(TENANT_ID, 'BRAND', BRAND_ID, null);
    const text = el().textContent ?? '';
    expect(text).toContain('Delivery');
    expect(text).toContain('Pickup');
    expect(text).toContain('Dine-in');
    expect(text).toContain('At risk 10 min before the promise');
    expect(text).toContain('At risk 2 min before the promise');
    expect(text).toContain('late 60 s after it');
    expect(text).toContain('late after 60 min');
  });

  it('marks a mode that owns no window as taking the default, showing the number it actually uses', async () => {
    await render(INHERITED_FROM_TENANT);

    // Dine-in: no window of its own, so the scalar's 12 minutes (720 s) with the default marker.
    expect(el().textContent).toContain('At risk 12 min (default) before the promise');
  });

  it('shows the document as inherited, naming where from, and offers to override rather than edit', async () => {
    await render(INHERITED_FROM_TENANT);

    expect(el().textContent).toContain('Version 3');
    expect(el().querySelectorAll('button.field__chip').length).toBe(3);
    expect(el().textContent).toContain('Override here');
    expect(el().textContent).not.toContain('Set at this level');
  });

  it('shows a document set at this level as set here with an edit action', async () => {
    await render(SET_AT_BRAND);

    expect(el().textContent).toContain('Set at this level');
    expect(button('Edit')).toBeTruthy();
  });

  it('carries each authored level’s version, approver and time into the trace popover', async () => {
    await render({
      ...SET_AT_BRAND,
      inspectedLevels: [
        {
          scopeType: 'BRAND',
          outcome: 'VALUE',
          version: 2,
          approvedByName: 'A. Karimov',
          validFrom: '2026-09-30T09:15:00Z',
        },
        {
          scopeType: 'TENANT',
          outcome: 'VALUE',
          version: 1,
          approvedByName: null,
          validFrom: '2026-08-12T05:20:00Z',
        },
        { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
      ],
    });

    (el().querySelector('button.field__chip') as HTMLButtonElement).click();
    fixture.detectChanges();

    const who = [...el().querySelectorAll('[data-testid="trace-who"]')].map((row) =>
      row.textContent?.trim(),
    );
    expect(who).toEqual([
      'Version 2 · A. Karimov · 30.09 14:15',
      'Version 1 · a person with no staff record here · 12.08 10:20',
    ]);
  });

  it('never offers "revert to inherited": a published version is not withdrawn', async () => {
    await render(SET_AT_BRAND);
    expect(el().textContent).not.toContain('Revert to inherited');

    await render(INHERITED_FROM_TENANT);
    expect(el().textContent).not.toContain('Revert to inherited');
  });

  it('shows the platform default without a version line when nothing was ever authored', async () => {
    await render(PLATFORM_DEFAULT);

    expect(el().textContent).not.toContain('Version');
    expect(el().textContent).toContain('At risk 5 min (default) before the promise');
  });

  it('says so when the read fails, rather than showing a blank editor', async () => {
    api.get.mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    fixture = TestBed.createComponent(LatenessPolicyCard);
    fixture.componentRef.setInput('tenantId', TENANT_ID);
    fixture.componentRef.setInput('scopeType', 'BRAND');
    fixture.componentRef.setInput('brandId', BRAND_ID);
    fixture.componentRef.setInput('locationId', null);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')).toBeTruthy();
    expect(el().querySelectorAll('q-inherited-field').length).toBe(0);
  });

  it('re-reads when the scope bar moves to another level, and when the scalar it defaults to changes', async () => {
    await render(INHERITED_FROM_TENANT);
    expect(api.get).toHaveBeenCalledTimes(1);

    fixture.componentRef.setInput('scopeType', 'LOCATION');
    fixture.componentRef.setInput('locationId', LOCATION_ID);
    fixture.detectChanges();
    await flush();
    expect(api.get).toHaveBeenLastCalledWith(TENANT_ID, 'LOCATION', BRAND_ID, LOCATION_ID);

    fixture.componentRef.setInput('reloadToken', 1);
    fixture.detectChanges();
    await flush();
    expect(api.get).toHaveBeenCalledTimes(3);
  });

  it('drops a read that lost the race with a newer one, so a slow answer cannot show the wrong scope', async () => {
    let releaseSlow: (view: LatenessEditorView) => void = () => undefined;
    api.get.mockImplementationOnce(
      () => new Promise<LatenessEditorView>((resolve) => (releaseSlow = resolve)),
    );
    api.get.mockResolvedValueOnce(SET_AT_BRAND);
    fixture = TestBed.createComponent(LatenessPolicyCard);
    fixture.componentRef.setInput('tenantId', TENANT_ID);
    fixture.componentRef.setInput('scopeType', 'TENANT');
    fixture.componentRef.setInput('brandId', BRAND_ID);
    fixture.componentRef.setInput('locationId', null);
    fixture.detectChanges();
    await flush();

    fixture.componentRef.setInput('scopeType', 'BRAND');
    fixture.detectChanges();
    await flush();
    releaseSlow(PLATFORM_DEFAULT);
    await flush();
    fixture.detectChanges();

    expect(el().textContent).toContain('Set at this level');
  });

  // ------------------------------------------------- scope switch and reload

  /** A GET that stays open until the test releases it, so the card is observed mid-load. */
  function pendingRead(): { release: (view: LatenessEditorView) => void } {
    const handle: { release: (view: LatenessEditorView) => void } = { release: () => undefined };
    api.get.mockImplementationOnce(
      () => new Promise<LatenessEditorView>((resolve) => (handle.release = resolve)),
    );
    return handle;
  }

  it('drops the previous scope’s document and its open form the moment the scope bar moves, not when the read returns', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    type('lateness-reason', 'a brand edit');
    const slow = pendingRead();

    fixture.componentRef.setInput('scopeType', 'LOCATION');
    fixture.componentRef.setInput('locationId', LOCATION_ID);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(el().querySelectorAll('fieldset').length, 'no form for a scope not read yet').toBe(0);
    expect(el().querySelectorAll('q-inherited-field').length).toBe(0);
    expect(
      [...el().querySelectorAll('button')].some((b) => b.textContent?.includes('Publish')),
    ).toBe(false);
    expect(el().textContent).toContain('Loading');

    slow.release(INHERITED_FROM_TENANT);
    await flush();
    fixture.detectChanges();

    expect(el().querySelectorAll('q-inherited-field').length).toBe(3);
    expect(el().querySelectorAll('fieldset').length).toBe(0);
    expect(api.publish).not.toHaveBeenCalled();
  });

  it('never carries the previous scope’s version into a publish at the new scope', async () => {
    await render(SET_AT_BRAND);
    const slow = pendingRead();
    fixture.componentRef.setInput('scopeType', 'LOCATION');
    fixture.componentRef.setInput('locationId', LOCATION_ID);
    fixture.detectChanges();
    await flush();
    slow.release(INHERITED_FROM_TENANT);
    await flush();
    fixture.detectChanges();
    await openForm();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 1, policyVersion: 1 });

    type('lateness-reason', 'location override');
    button('Publish').click();
    await flush();

    expect(api.publish).toHaveBeenCalledTimes(1);
    expect(api.publish.mock.calls[0][1].scopeType).toBe('LOCATION');
    expect(api.publish.mock.calls[0][1].locationId).toBe(LOCATION_ID);
    // The location has authored nothing: the brand's version 2 is not its version.
    expect(api.publish.mock.calls[0][1].expectedVersion).toBeNull();
  });

  it('says a publication landed, at the scope the form was opened at, so the page can confirm it (row X.1)', async () => {
    await render(SET_AT_BRAND);
    const landed: unknown[] = [];
    fixture.componentInstance.published.subscribe((where) => landed.push(where));
    button('Edit').click();
    fixture.detectChanges();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 3, policyVersion: 3 });
    type('lateness-reason', 'a brand edit');

    // The bar moves on while the request is in flight.
    const publishing = button('Publish');
    publishing.click();
    fixture.componentRef.setInput('scopeType', 'LOCATION');
    fixture.componentRef.setInput('locationId', LOCATION_ID);
    await flush();

    expect(landed).toEqual([{ scopeType: 'BRAND', brandId: BRAND_ID, locationId: null }]);
  });

  it('says nothing when the publication is refused', async () => {
    await render(SET_AT_BRAND);
    const landed: unknown[] = [];
    fixture.componentInstance.published.subscribe((where) => landed.push(where));
    button('Edit').click();
    fixture.detectChanges();
    api.publish.mockRejectedValue(new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null));
    type('lateness-reason', 'a brand edit');

    button('Publish').click();
    await flush();

    expect(landed).toEqual([]);
  });

  it('keeps an open draft when only the scalar it defaults to changes, and still publishes against the version it was opened at', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    type('lateness-delivery-atRiskMinutes', '25');
    type('lateness-reason', 'busier Fridays');

    // Another operator published version 3 meanwhile, and the scalar moved to 15 minutes.
    api.get.mockResolvedValue({
      ...SET_AT_BRAND,
      atRiskDefault: { seconds: 900, source: 'SCALAR' },
      currentVersionAtScope: 3,
      policyVersion: 3,
    });
    fixture.componentRef.setInput('reloadToken', 1);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(el().querySelectorAll('fieldset').length, 'the form stays open').toBe(3);
    expect(input('lateness-delivery-atRiskMinutes').value).toBe('25');
    expect(input('lateness-reason').value).toBe('busier Fridays');
    expect(
      input('lateness-dineIn-atRiskMinutes').placeholder,
      'the default follows the scalar',
    ).toBe('15');

    api.publish.mockRejectedValue(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, { status: 409, code: 'STALE_VERSION' }, null),
    );
    button('Publish').click();
    await flush();
    fixture.detectChanges();

    // Version 2 is what the operator saw when they began: the server, not the card, refuses the lost update.
    expect(api.publish.mock.calls[0][1].expectedVersion).toBe(2);
    expect(el().textContent).toContain('Reload and discard my changes');
  });

  it('keeps an open draft when the background re-read fails', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    type('lateness-reason', 'still typing');

    api.get.mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null));
    fixture.componentRef.setInput('reloadToken', 1);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(el().querySelectorAll('fieldset').length).toBe(3);
    expect(input('lateness-reason').value).toBe('still typing');
  });

  it('does not let an older read overwrite the document just published', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    type('lateness-reason', 'tighten');
    const slow = pendingRead();
    fixture.componentRef.setInput('reloadToken', 1);
    fixture.detectChanges();
    await flush();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 3, policyVersion: 3 });

    button('Publish').click();
    await flush();
    slow.release(SET_AT_BRAND); // still version 2: it left before the publish landed
    await flush();
    fixture.detectChanges();

    expect(el().textContent).toContain('Version 3');
  });

  it('does not show a publish that came back for a scope the bar has since left', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    type('lateness-reason', 'brand edit');
    let landed: (view: LatenessEditorView) => void = () => undefined;
    api.publish.mockImplementation(
      () => new Promise<LatenessEditorView>((resolve) => (landed = resolve)),
    );
    button('Publish').click();
    fixture.detectChanges();

    api.get.mockResolvedValue(INHERITED_FROM_TENANT);
    fixture.componentRef.setInput('scopeType', 'LOCATION');
    fixture.componentRef.setInput('locationId', LOCATION_ID);
    fixture.detectChanges();
    await flush();
    landed({ ...SET_AT_BRAND, currentVersionAtScope: 9, policyVersion: 9 });
    await flush();
    fixture.detectChanges();

    expect(el().textContent).not.toContain('Version 9');
    expect(el().textContent).toContain('Version 3');
  });

  // ---------------------------------------------------------------- editing

  it('opens one form for all three modes, blank windows standing for the default it names', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();

    expect(el().querySelectorAll('fieldset').length).toBe(3);
    expect(input('lateness-delivery-atRiskMinutes').value).toBe('10');
    expect(input('lateness-pickup-atRiskMinutes').value).toBe('2');
    expect(input('lateness-dineIn-atRiskMinutes').value).toBe('');
    expect(input('lateness-dineIn-atRiskMinutes').placeholder).toBe('12');
    expect(input('lateness-delivery-lateAfterSeconds').value).toBe('60');
    expect(input('lateness-delivery-fallbackMinutes').value).toBe('60');
    expect(el().textContent).toContain('Leave “Warn before the promise” empty');
    expect(el().textContent).toContain('the “Warn before the promised time” value above');
  });

  it('names the platform default as the fallback when no scalar was set', async () => {
    await render(PLATFORM_DEFAULT);
    button('Override here').click();
    fixture.detectChanges();

    expect(input('lateness-delivery-atRiskMinutes').placeholder).toBe('5');
    expect(el().textContent).toContain('the platform default');
  });

  it('publishes seconds: minutes times sixty, a blank window as null, the reason trimmed', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 1, policyVersion: 1 });

    type('lateness-delivery-atRiskMinutes', '15');
    type('lateness-pickup-atRiskMinutes', '');
    type('lateness-pickup-lateAfterSeconds', '90');
    type('lateness-dineIn-fallbackMinutes', '20');
    type('lateness-reason', '  the counter is slow on Fridays  ');
    button('Publish').click();
    await flush();

    expect(api.publish).toHaveBeenCalledTimes(1);
    expect(api.publish).toHaveBeenCalledWith(TENANT_ID, {
      scopeType: 'BRAND',
      brandId: BRAND_ID,
      locationId: null,
      delivery: { atRiskBeforeSeconds: 900, lateAfterSeconds: 60, noPromiseFallbackSeconds: 3600 },
      pickup: { atRiskBeforeSeconds: null, lateAfterSeconds: 90, noPromiseFallbackSeconds: 1800 },
      dineIn: { atRiskBeforeSeconds: null, lateAfterSeconds: 30, noPromiseFallbackSeconds: 1200 },
      expectedVersion: null,
      reason: 'the counter is slow on Fridays',
    });
  });

  it('sends the version the form was opened at, so a second operator’s save is caught server-side', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 3, policyVersion: 3 });

    type('lateness-reason', 'tighten');
    button('Publish').click();
    await flush();

    expect(api.publish.mock.calls[0][1].expectedVersion).toBe(2);
  });

  it('shows the saved document and closes the form on success', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 1, policyVersion: 1 });

    type('lateness-reason', 'override');
    button('Publish').click();
    await flush();
    fixture.detectChanges();

    expect(el().querySelectorAll('fieldset').length).toBe(0);
    expect(el().textContent).toContain('Set at this level');
  });

  it('keeps Publish disabled until every box is valid and a reason is given', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    const publish = () => button('Publish');

    expect(publish().disabled).toBe(true); // no reason yet
    type('lateness-reason', 'because');
    expect(publish().disabled).toBe(false);

    for (const [id, bad] of [
      ['lateness-delivery-atRiskMinutes', '1.5'],
      ['lateness-delivery-atRiskMinutes', '1441'],
      ['lateness-delivery-atRiskMinutes', '-1'],
      ['lateness-pickup-lateAfterSeconds', '86401'],
      ['lateness-pickup-lateAfterSeconds', ''],
      ['lateness-dineIn-fallbackMinutes', '0'],
      ['lateness-dineIn-fallbackMinutes', '20.5'],
    ] as const) {
      const before = input(id).value;
      type(id, bad);
      expect(publish().disabled, `${id} = "${bad}"`).toBe(true);
      expect(input(id).getAttribute('aria-invalid')).toBe('true');
      type(id, before);
      expect(publish().disabled, `${id} restored`).toBe(false);
    }
  });

  // ------------------------------------------------ ADR 0150: the blank fallback

  it('marks a mode that owns no fallback as taking the default, showing the number it actually uses', async () => {
    await render(BLANK_FALLBACK_FROM_TENANT);

    // Pickup owns none: the scalar's 25 minutes (1500 s) with the default marker; delivery keeps its own.
    expect(el().textContent).toContain('no promise: late after 25 min (default)');
    expect(el().textContent).toContain('no promise: late after 60 min');
  });

  it('shows the platform default fallback with its marker when nothing was ever authored', async () => {
    await render(PLATFORM_DEFAULT);

    expect(el().textContent).toContain('no promise: late after 45 min (default)');
  });

  it('opens a blank fallback as blank, with the default it stands for as the placeholder, and says where it comes from', async () => {
    await render(BLANK_FALLBACK_FROM_TENANT);
    await openForm();

    expect(input('lateness-pickup-fallbackMinutes').value).toBe('');
    expect(input('lateness-pickup-fallbackMinutes').placeholder).toBe('25');
    expect(input('lateness-delivery-fallbackMinutes').value).toBe('60');
    expect(el().textContent).toContain(
      'Leave “No promise: late after” empty to use the default: 25 min',
    );
    expect(el().textContent).toContain(
      'the “An order with no promised time is late after” value above',
    );
  });

  it('names the platform’s forty-five minutes when the late-order threshold was never set', async () => {
    await render(PLATFORM_DEFAULT);
    button('Override here').click();
    fixture.detectChanges();

    expect(input('lateness-delivery-fallbackMinutes').placeholder).toBe('45');
    expect(el().textContent).toContain(
      'Leave “No promise: late after” empty to use the default: 45 min (the platform default)',
    );
  });

  it('publishes a blank fallback as null, not as zero and not as the default’s number', async () => {
    await render(BLANK_FALLBACK_FROM_TENANT);
    await openForm();
    api.publish.mockResolvedValue({ ...SET_AT_BRAND, currentVersionAtScope: 1, policyVersion: 1 });

    type('lateness-delivery-fallbackMinutes', '');
    type('lateness-reason', 'let the threshold decide');
    button('Publish').click();
    await flush();

    const sent = api.publish.mock.calls[0][1];
    expect(sent.delivery.noPromiseFallbackSeconds).toBeNull();
    expect(sent.pickup.noPromiseFallbackSeconds).toBeNull();
    expect(sent.dineIn.noPromiseFallbackSeconds).toBe(1200);
  });

  it('refuses a fallback that is not a whole number of minutes between one and a day, and accepts a blank', async () => {
    await render(BLANK_FALLBACK_FROM_TENANT);
    await openForm();
    type('lateness-reason', 'because');

    for (const bad of ['0', '20.5', '1441', '-3']) {
      type('lateness-pickup-fallbackMinutes', bad);
      expect(button('Publish').disabled, bad).toBe(true);
      expect(input('lateness-pickup-fallbackMinutes').getAttribute('aria-invalid')).toBe('true');
    }
    type('lateness-pickup-fallbackMinutes', '');
    expect(button('Publish').disabled, 'blank').toBe(false);
  });

  it('accepts zero minutes as a window and zero seconds of grace', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    api.publish.mockResolvedValue(SET_AT_BRAND);

    type('lateness-dineIn-atRiskMinutes', '0');
    type('lateness-dineIn-lateAfterSeconds', '0');
    type('lateness-reason', 'why not');
    button('Publish').click();
    await flush();

    expect(api.publish.mock.calls[0][1].dineIn).toEqual({
      atRiskBeforeSeconds: 0,
      lateAfterSeconds: 0,
      noPromiseFallbackSeconds: 1200,
    });
  });

  it('cancelling drops the draft and leaves the document as it was', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    type('lateness-delivery-atRiskMinutes', '99');

    button('Cancel').click();
    fixture.detectChanges();

    expect(api.publish).not.toHaveBeenCalled();
    expect(el().querySelectorAll('fieldset').length).toBe(0);
    await openForm();
    expect(input('lateness-delivery-atRiskMinutes').value).toBe('10');
  });

  // ------------------------------------------------------------ concurrency

  it('on a stale version says so and offers a reload that discards the edit, never a blind retry', async () => {
    await render(SET_AT_BRAND);
    button('Edit').click();
    fixture.detectChanges();
    api.publish.mockRejectedValue(
      new ApiError(ApiErrorCode.STALE_VERSION, 409, { status: 409, code: 'STALE_VERSION' }, null),
    );
    type('lateness-delivery-atRiskMinutes', '30');
    type('lateness-reason', 'slow one');
    button('Publish').click();
    await flush();
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')).toBeTruthy();
    expect(el().querySelectorAll('fieldset').length).toBe(3); // the edit is still on screen
    expect(api.publish).toHaveBeenCalledTimes(1);

    const newer: LatenessEditorView = {
      ...SET_AT_BRAND,
      currentVersionAtScope: 3,
      policyVersion: 3,
    };
    api.get.mockResolvedValue(newer);
    button('Reload and discard my changes').click();
    await flush();
    fixture.detectChanges();

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(el().querySelectorAll('fieldset').length).toBe(0);
    expect(el().textContent).toContain('Version 3');
    expect(api.publish).toHaveBeenCalledTimes(1);
  });

  it('shows another refusal without the reload offer, keeping the form open to correct', async () => {
    await render(INHERITED_FROM_TENANT);
    await openForm();
    api.publish.mockRejectedValue(
      new ApiError(
        ApiErrorCode.VALIDATION_FAILED,
        400,
        {
          status: 400,
          code: 'VALIDATION_FAILED',
          detail: 'PICKUP lateAfterSeconds must be between 0 and 86400',
        },
        null,
      ),
    );

    type('lateness-reason', 'x');
    button('Publish').click();
    await flush();
    fixture.detectChanges();

    expect(el().textContent).toContain('PICKUP lateAfterSeconds must be between 0 and 86400');
    expect(el().textContent).not.toContain('Reload and discard my changes');
    expect(el().querySelectorAll('fieldset').length).toBe(3);
  });
});
