import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { MxikReferenceRow } from './catalog-domain';
import { MxikPicker } from './mxik-picker';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };
const MILK_CODE = '04001001001000000';
const BREAD_CODE = '10701001001000000';

const MILK: MxikReferenceRow = {
  code: MILK_CODE,
  labelRu: 'Молоко',
  labelUz: 'Sut',
  labelEn: 'Milk',
  defaultPackageCodes: ['1500316'],
  validFrom: '2024-01-01',
};
const BREAD: MxikReferenceRow = {
  code: BREAD_CODE,
  labelRu: 'Хлеб',
  labelUz: 'Non',
  labelEn: null,
  defaultPackageCodes: ['1500175', '1500999'],
  validFrom: '2024-01-01',
};

describe('MxikPicker', () => {
  let search: ReturnType<typeof vi.fn>;

  function render(
    options: { scope?: BrandScope | null; code?: string } = {},
  ): ReturnType<typeof TestBed.createComponent<MxikPicker>> {
    const fixture = TestBed.createComponent(MxikPicker);
    fixture.componentRef.setInput('scope', options.scope === undefined ? SCOPE : options.scope);
    fixture.componentRef.setInput('code', options.code ?? '');
    fixture.detectChanges();
    return fixture;
  }

  function field(fixture: ReturnType<typeof render>): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-combobox-input"]',
    ) as HTMLInputElement;
  }

  async function type(fixture: ReturnType<typeof render>, text: string): Promise<void> {
    const input = field(fixture);
    input.value = text;
    input.dispatchEvent(new Event('input'));
    // The combobox debounces its search; this is past it.
    await vi.advanceTimersByTimeAsync(300);
    fixture.detectChanges();
  }

  /** Each option as «label | sublabel»: the two spans carry no whitespace between them in the DOM. */
  function optionTexts(fixture: ReturnType<typeof render>): string[] {
    return [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="q-combobox-option"]',
      ),
    ].map((option) => {
      const label = option.querySelector('.q-body-sm')?.textContent?.trim() ?? '';
      const sublabel = option.querySelector('.q-combobox__sublabel')?.textContent?.trim() ?? '';
      return `${label} | ${sublabel}`;
    });
  }

  beforeEach(() => {
    vi.useFakeTimers();
    search = vi.fn(() => of([MILK, BREAD]));
    TestBed.configureTestingModule({
      providers: [{ provide: CatalogApi, useValue: { searchMxikReference: search } }],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  it('treats digits as a code: reported without the spaces a spreadsheet adds, and looked up too', async () => {
    const fixture = render();
    const codes: string[] = [];
    fixture.componentInstance.codeChange.subscribe((code) => codes.push(code));

    await type(fixture, '04001 001 001');

    expect(codes).toEqual(['04001001001']);
    expect(field(fixture).value).toBe('04001001001');
    expect(search).toHaveBeenCalledWith(SCOPE, '04001 001 001');
  });

  it('treats a name as a search and not as a value: the code is empty while it is typed', async () => {
    const fixture = render({ code: MILK_CODE });
    const codes: string[] = [];
    fixture.componentInstance.codeChange.subscribe((code) => codes.push(code));

    await type(fixture, 'milk');

    expect(codes).toEqual(['']);
    expect(search).toHaveBeenCalledWith(SCOPE, 'milk');
    expect(optionTexts(fixture)).toEqual([
      `${MILK_CODE} — Milk | Sut`,
      `${BREAD_CODE} — Хлеб | Non`,
    ]);
  });

  it('words the options in the console language, with the other language beneath', async () => {
    TestBed.inject(I18n).setLocale('ru');
    const fixture = render();

    await type(fixture, 'мол');

    expect(optionTexts(fixture)[0]).toBe(`${MILK_CODE} — Молоко | Sut`);

    TestBed.inject(I18n).setLocale('uz-Latn');
    fixture.detectChanges();
    await type(fixture, 'sut');
    expect(optionTexts(fixture)[0]).toBe(`${MILK_CODE} — Sut | Молоко`);
  });

  it('writes the chosen row’s code and says which row it was', async () => {
    const fixture = render();
    const codes: string[] = [];
    const picked: MxikReferenceRow[] = [];
    fixture.componentInstance.codeChange.subscribe((code) => codes.push(code));
    fixture.componentInstance.picked.subscribe((row) => picked.push(row));
    await type(fixture, 'bread');

    (
      (fixture.nativeElement as HTMLElement).querySelectorAll(
        '[data-testid="q-combobox-option"]',
      )[1] as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(codes.at(-1)).toBe(BREAD_CODE);
    expect(picked).toEqual([BREAD]);
    expect(field(fixture).value).toBe(BREAD_CODE);
    expect(optionTexts(fixture)).toEqual([]);
  });

  it('follows a code its owner sets from outside, but not over a name being looked up', async () => {
    const fixture = render();

    fixture.componentRef.setInput('code', MILK_CODE);
    fixture.detectChanges();
    expect(field(fixture).value).toBe(MILK_CODE);

    await type(fixture, 'bread');
    fixture.componentRef.setInput('code', '');
    fixture.detectChanges();
    expect(field(fixture).value).toBe('bread');

    fixture.componentRef.setInput('code', BREAD_CODE);
    fixture.detectChanges();
    expect(field(fixture).value).toBe(BREAD_CODE);
  });

  it('asks nothing for a single character, and nothing at all without a scope', async () => {
    const fixture = render();
    await type(fixture, 'm');
    expect(search).not.toHaveBeenCalled();

    const unscoped = render({ scope: null });
    await type(unscoped, 'milk');
    expect(search).not.toHaveBeenCalled();
    expect(optionTexts(unscoped)).toEqual([]);
  });

  it('keeps the answer to the latest question when an earlier one arrives late', async () => {
    const first = new Subject<readonly MxikReferenceRow[]>();
    const second = new Subject<readonly MxikReferenceRow[]>();
    search.mockReturnValueOnce(first).mockReturnValueOnce(second);
    const fixture = render();

    await type(fixture, 'mi');
    await type(fixture, 'milk');
    second.next([MILK]);
    second.complete();
    await vi.advanceTimersByTimeAsync(0);
    first.next([BREAD]);
    first.complete();
    await vi.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    expect(optionTexts(fixture)).toEqual([`${MILK_CODE} — Milk | Sut`]);
  });

  it('survives a failing lookup: no options, and a code can still be typed', async () => {
    search.mockReturnValue(throwError(() => new Error('boom')));
    const fixture = render();
    const codes: string[] = [];
    fixture.componentInstance.codeChange.subscribe((code) => codes.push(code));

    await type(fixture, MILK_CODE);

    expect(optionTexts(fixture)).toEqual([]);
    expect(codes).toEqual([MILK_CODE]);
  });

  it('says there is nothing in the list when the reference is empty, and still takes a typed code', async () => {
    search.mockReturnValue(of([]));
    const fixture = render();
    const codes: string[] = [];
    fixture.componentInstance.codeChange.subscribe((code) => codes.push(code));

    await type(fixture, MILK_CODE);

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-combobox-no-results"]'),
    ).not.toBeNull();
    expect(codes).toEqual([MILK_CODE]);
  });

  it('marks its field invalid when its owner says the code is wrong', () => {
    const fixture = render();
    fixture.componentRef.setInput('invalid', true);
    fixture.detectChanges();

    expect(field(fixture).getAttribute('aria-invalid')).toBe('true');
  });
});
