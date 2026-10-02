import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { PhysicalAttributesRequest, PhysicalAttributesView } from './physical-attributes';
import { VariantPhysicalAttributes } from './variant-physical-attributes';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

const NOTHING: PhysicalAttributesView = { catchweight: false, splittable: false, version: 0 };

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

interface Fake {
  physicalAttributes: ReturnType<typeof vi.fn>;
  setPhysicalAttributes: ReturnType<typeof vi.fn>;
}

function configure(overrides: Partial<Fake> = {}): Fake {
  const api: Fake = {
    physicalAttributes: vi.fn(() => of(NOTHING)),
    setPhysicalAttributes: vi.fn(
      (
        _scope: unknown,
        _variantId: string,
        request: PhysicalAttributesRequest,
        expected: number,
      ): Observable<PhysicalAttributesView> => of({ ...request, version: expected + 1 }),
    ),
    ...overrides,
  };
  TestBed.configureTestingModule({
    imports: [VariantPhysicalAttributes],
    providers: [{ provide: CatalogApi, useValue: api }],
  });
  TestBed.inject(I18n).setLocale('en');
  return api;
}

async function open(markingRequired = false) {
  const fixture = TestBed.createComponent(VariantPhysicalAttributes);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('variantId', 'v1');
  fixture.componentRef.setInput('label', 'Medovik cake');
  fixture.componentRef.setInput('markingRequired', markingRequired);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

function el(fixture: { nativeElement: unknown }): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function field<T extends HTMLElement>(fixture: { nativeElement: unknown }, testId: string): T {
  const found = el(fixture).querySelector<T>(`[data-testid="${testId}"]`);
  if (!found) {
    throw new Error(`no element ${testId}`);
  }
  return found;
}

function maybe(fixture: { nativeElement: unknown }, testId: string): HTMLElement | null {
  return el(fixture).querySelector<HTMLElement>(`[data-testid="${testId}"]`);
}

async function type(
  fixture: { detectChanges(): void },
  input: HTMLInputElement,
  value: string,
): Promise<void> {
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

async function choose(
  fixture: { detectChanges(): void },
  select: HTMLSelectElement,
  value: string,
): Promise<void> {
  select.value = value;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

function tick(fixture: { detectChanges(): void }, box: HTMLInputElement): void {
  box.click();
  fixture.detectChanges();
}

describe('VariantPhysicalAttributes', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reads the variant’s attributes and opens a variant with none on an empty form', async () => {
    const api = configure();
    const fixture = await open();

    expect(api.physicalAttributes).toHaveBeenCalledWith(SCOPE, 'v1');
    expect(el(fixture).textContent).toContain('Medovik cake');
    expect(field<HTMLSelectElement>(fixture, 'physical-measure').value).toBe('NONE');
    expect(maybe(fixture, 'physical-quantum')).toBeNull();
    expect(maybe(fixture, 'physical-portion')).toBeNull();
    expect(field<HTMLButtonElement>(fixture, 'physical-save').disabled).toBe(true);
  });

  it('opens a stored set on the values it holds', async () => {
    configure({
      physicalAttributes: vi.fn(() =>
        of({
          netWeightGrams: 1200,
          catchweight: true,
          catchweightQuantumGrams: 100,
          catchweightNominalGrams: 1250,
          splittable: true,
          portionSize: 0.5,
          caloriesKcalPer100: 215.5,
          proteinGramsPer100: 8.2,
          fatGramsPer100: 10,
          carbohydratesGramsPer100: 31,
          version: 4,
        }),
      ),
    });
    const fixture = await open();

    expect(field<HTMLSelectElement>(fixture, 'physical-measure').value).toBe('WEIGHT');
    expect(field<HTMLInputElement>(fixture, 'physical-measure-value').value).toBe('1200');
    expect(field<HTMLInputElement>(fixture, 'physical-catchweight').checked).toBe(true);
    expect(field<HTMLInputElement>(fixture, 'physical-quantum').value).toBe('100');
    expect(field<HTMLInputElement>(fixture, 'physical-nominal').value).toBe('1250');
    expect(field<HTMLInputElement>(fixture, 'physical-splittable').checked).toBe(true);
    expect(field<HTMLInputElement>(fixture, 'physical-portion').value).toBe('0.5');
    expect(field<HTMLInputElement>(fixture, 'physical-calories').value).toBe('215.5');
    expect(field<HTMLButtonElement>(fixture, 'physical-save').disabled).toBe(true);
  });

  it('writes the whole set under the version it read, then under the version the write returned', async () => {
    const api = configure({
      physicalAttributes: vi.fn(() => of({ ...NOTHING, version: 2 })),
    });
    const fixture = await open();

    await choose(fixture, field<HTMLSelectElement>(fixture, 'physical-measure'), 'WEIGHT');
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-measure-value'), '350');
    const save = field<HTMLButtonElement>(fixture, 'physical-save');
    expect(save.disabled).toBe(false);
    save.click();
    await flush();
    fixture.detectChanges();

    expect(api.setPhysicalAttributes).toHaveBeenCalledTimes(1);
    const [scope, variantId, request, expected] = api.setPhysicalAttributes.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(variantId).toBe('v1');
    expect(expected).toBe(2);
    expect(request).toMatchObject({
      netWeightGrams: 350,
      netVolumeMillilitres: null,
      catchweight: false,
      splittable: false,
    });
    expect(el(fixture).textContent).toContain('Saved');
    expect(field<HTMLButtonElement>(fixture, 'physical-save').disabled).toBe(true);

    // A second edit goes under the version the first write returned.
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-measure-value'), '400');
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();
    expect(api.setPhysicalAttributes.mock.calls[1][3]).toBe(3);
  });

  it('tells the page a save landed, so its readiness rail can look again', async () => {
    configure();
    const fixture = TestBed.createComponent(VariantPhysicalAttributes);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('variantId', 'v1');
    fixture.componentRef.setInput('label', 'Cake');
    fixture.componentRef.setInput('markingRequired', false);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    await choose(fixture, field<HTMLSelectElement>(fixture, 'physical-measure'), 'VOLUME');
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-measure-value'), '330');
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();

    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('asks for the quantum and the estimated weight once the variant is sold by weight', async () => {
    const api = configure();
    const fixture = await open();

    tick(fixture, field<HTMLInputElement>(fixture, 'physical-catchweight'));

    expect(field<HTMLInputElement>(fixture, 'physical-quantum')).toBeTruthy();
    expect(field<HTMLInputElement>(fixture, 'physical-nominal')).toBeTruthy();
    expect(el(fixture).textContent).toContain(
      'A catchweight variant needs the weight its price is quoted per',
    );
    expect(field<HTMLButtonElement>(fixture, 'physical-save').disabled).toBe(true);

    await type(fixture, field<HTMLInputElement>(fixture, 'physical-quantum'), '100');
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-nominal'), '1200');
    expect(maybe(fixture, 'physical-error-catchweightQuantum')).toBeNull();
    expect(maybe(fixture, 'physical-error-catchweightNominal')).toBeNull();
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();

    expect(api.setPhysicalAttributes.mock.calls[0][2]).toMatchObject({
      catchweight: true,
      catchweightQuantumGrams: 100,
      catchweightNominalGrams: 1200,
    });
  });

  it('takes a portion size with a comma, on a splittable variant only', async () => {
    const api = configure();
    const fixture = await open();
    expect(maybe(fixture, 'physical-portion')).toBeNull();

    tick(fixture, field<HTMLInputElement>(fixture, 'physical-splittable'));
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-portion'), '0,5');
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();

    expect(api.setPhysicalAttributes.mock.calls[0][2]).toMatchObject({
      splittable: true,
      portionSize: 0.5,
    });
  });

  it('marks a wrong figure on its own field and refuses to send it', async () => {
    const api = configure();
    const fixture = await open();

    await type(fixture, field<HTMLInputElement>(fixture, 'physical-protein'), '120');

    expect(field<HTMLElement>(fixture, 'physical-error-protein').textContent).toContain(
      'Grams per 100 g are between 0 and 100',
    );
    expect(field<HTMLButtonElement>(fixture, 'physical-save').disabled).toBe(true);
    expect(api.setPhysicalAttributes).not.toHaveBeenCalled();
  });

  it('labels the nutrition per 100 ml for a variant measured by volume', async () => {
    configure();
    const fixture = await open();
    expect(el(fixture).textContent).toContain('per 100 g');

    await choose(fixture, field<HTMLSelectElement>(fixture, 'physical-measure'), 'VOLUME');

    expect(el(fixture).textContent).toContain('per 100 ml');
    expect(el(fixture).textContent).not.toContain('per 100 g');
  });

  it('warns, before publication does, that a marked good cannot be sold by weight or in parts', async () => {
    configure();
    const fixture = await open(true);
    expect(maybe(fixture, 'physical-marking-conflict')).toBeNull();

    tick(fixture, field<HTMLInputElement>(fixture, 'physical-splittable'));

    expect(field<HTMLElement>(fixture, 'physical-marking-conflict').textContent).toContain(
      'Data Matrix',
    );
  });

  it('does not warn about marking on a variant that is not marked', async () => {
    configure();
    const fixture = await open(false);

    tick(fixture, field<HTMLInputElement>(fixture, 'physical-splittable'));

    expect(maybe(fixture, 'physical-marking-conflict')).toBeNull();
  });

  it('puts a refusal the server names on the field it names, and clears it when the field changes', async () => {
    configure({
      setPhysicalAttributes: vi.fn(() =>
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.VALIDATION_FAILED,
              400,
              {
                status: 400,
                code: ApiErrorCode.VALIDATION_FAILED,
                reason: 'PORTION_SIZE_TOO_PRECISE',
              },
              null,
            ),
        ),
      ),
    });
    const fixture = await open();
    tick(fixture, field<HTMLInputElement>(fixture, 'physical-splittable'));
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-portion'), '0.5');
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();
    fixture.detectChanges();

    expect(field<HTMLElement>(fixture, 'physical-error-portion').textContent).toContain(
      'at most three decimals',
    );

    await type(fixture, field<HTMLInputElement>(fixture, 'physical-portion'), '0.25');
    expect(maybe(fixture, 'physical-error-portion')).toBeNull();
  });

  it('reloads the latest values and says so when someone else saved first', async () => {
    const reads = vi
      .fn()
      .mockReturnValueOnce(of({ ...NOTHING, version: 1 }))
      .mockReturnValueOnce(of({ ...NOTHING, netWeightGrams: 500, version: 2 }));
    configure({
      physicalAttributes: reads,
      setPhysicalAttributes: vi.fn(() =>
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.STALE_VERSION,
              409,
              { status: 409, code: ApiErrorCode.STALE_VERSION },
              null,
            ),
        ),
      ),
    });
    const fixture = await open();
    await choose(fixture, field<HTMLSelectElement>(fixture, 'physical-measure'), 'VOLUME');
    await type(fixture, field<HTMLInputElement>(fixture, 'physical-measure-value'), '330');
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();
    fixture.detectChanges();

    expect(reads).toHaveBeenCalledTimes(2);
    expect(field<HTMLElement>(fixture, 'physical-notice').textContent).toContain(
      'Someone else changed these attributes',
    );
    expect(field<HTMLSelectElement>(fixture, 'physical-measure').value).toBe('WEIGHT');
    expect(field<HTMLInputElement>(fixture, 'physical-measure-value').value).toBe('500');
  });

  it('says plainly that clearing everything removes the stored row, and sends the empty set', async () => {
    const api = configure({
      physicalAttributes: vi.fn(() => of({ ...NOTHING, netWeightGrams: 300, version: 5 })),
    });
    const fixture = await open();
    expect(maybe(fixture, 'physical-clear-hint')).toBeNull();

    await choose(fixture, field<HTMLSelectElement>(fixture, 'physical-measure'), 'NONE');

    expect(maybe(fixture, 'physical-clear-hint')).toBeTruthy();
    field<HTMLButtonElement>(fixture, 'physical-save').click();
    await flush();
    expect(api.setPhysicalAttributes.mock.calls[0][2]).toEqual({
      netWeightGrams: null,
      netVolumeMillilitres: null,
      catchweight: false,
      catchweightQuantumGrams: null,
      catchweightNominalGrams: null,
      splittable: false,
      portionSize: null,
      caloriesKcalPer100: null,
      proteinGramsPer100: null,
      fatGramsPer100: null,
      carbohydratesGramsPer100: null,
    });
    expect(api.setPhysicalAttributes.mock.calls[0][3]).toBe(5);
  });

  it('a variant the operator cannot read says so instead of showing an empty form', async () => {
    configure({
      physicalAttributes: vi.fn(() =>
        throwError(
          () =>
            new ApiError(
              ApiErrorCode.INSUFFICIENT_CAPABILITY,
              403,
              { status: 403, code: ApiErrorCode.INSUFFICIENT_CAPABILITY },
              null,
            ),
        ),
      ),
    });
    const fixture = await open();

    expect(el(fixture).textContent).toContain('You do not have access to these attributes');
    expect(maybe(fixture, 'physical-save')).toBeNull();
  });

  it('offers a retry when the read fails, and shows the form once it works', async () => {
    const reads = vi
      .fn()
      .mockReturnValueOnce(
        throwError(() => new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, null)),
      )
      .mockReturnValueOnce(of(NOTHING));
    configure({ physicalAttributes: reads });
    const fixture = await open();
    expect(el(fixture).textContent).toContain('Could not load these attributes');

    field<HTMLButtonElement>(fixture, 'physical-retry').click();
    await flush();
    fixture.detectChanges();

    expect(maybe(fixture, 'physical-measure')).toBeTruthy();
  });
});
