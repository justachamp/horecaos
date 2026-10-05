import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { CatalogApi } from '../../catalog/catalog-api';
import { MxikReferenceRow } from '../../catalog/catalog-domain';
import {
  BACKFILL_BATCH_SIZE,
  FiscalBackfillEditor,
  MXIK_PATTERN,
  PACKAGE_CODE_PATTERN,
  isMissingCodes,
} from './fiscal-backfill-editor';
import {
  FiscalBackfillItem,
  FiscalBackfillOutcome,
  FiscalCategoryDefault,
  FiscalCoverageNode,
  FiscalizationApi,
} from './fiscalization-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const MXIK_A = '10706001001000000';
const PACKAGE_A = '1500316';
const MXIK_B = '10101001001000000';

function variant(id: string, overrides: Partial<FiscalCoverageNode> = {}): FiscalCoverageNode {
  return {
    nodeType: 'VARIANT',
    nodeId: id,
    name: `Dish ${id}`,
    categoryName: 'Burgers',
    locationCount: 2,
    categoryId: 'cat-burgers',
    mxikCode: null,
    packageCode: null,
    ...overrides,
  };
}

const BARE = variant('bare');
const HALF = variant('half', { mxikCode: MXIK_B });
const COLA = variant('cola', { categoryName: 'Drinks', categoryId: 'cat-drinks' });
const LOOSE = variant('loose', { categoryName: null, categoryId: null });
/** Both codes, but the coverage read still lists it (no unit or fiscal name): not this editor's row. */
const CODES_ONLY = variant('codes-only', { mxikCode: MXIK_A, packageCode: PACKAGE_A });
const OPTION: FiscalCoverageNode = {
  nodeType: 'MODIFIER_OPTION',
  nodeId: 'option',
  name: 'Cheese',
  categoryName: null,
  locationCount: 0,
};
const FEE: FiscalCoverageNode = {
  nodeType: 'FEE',
  nodeId: 'fee',
  name: null,
  categoryName: null,
  locationCount: 0,
};

const BURGER_DEFAULT: FiscalCategoryDefault = {
  categoryId: 'cat-burgers',
  categoryName: 'Burgers',
  mxikCode: MXIK_A,
  packageCode: PACKAGE_A,
  agreeingCount: 2,
  sampleSize: 3,
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function applied(items: readonly FiscalBackfillItem[]): FiscalBackfillOutcome[] {
  return items.map((item) => ({
    nodeType: item.nodeType,
    nodeId: item.nodeId,
    status: 'CLASSIFIED' as const,
  }));
}

describe('isMissingCodes', () => {
  it('is true for a dish or a modifier option short of either code, and false for everything the editor is not for', () => {
    expect(isMissingCodes(BARE)).toBe(true);
    expect(isMissingCodes(HALF)).toBe(true);
    expect(isMissingCodes(variant('pkg-only', { packageCode: PACKAGE_A }))).toBe(true);
    expect(isMissingCodes(CODES_ONLY)).toBe(false);
    expect(isMissingCodes(OPTION)).toBe(true);
    expect(isMissingCodes({ ...OPTION, mxikCode: MXIK_A, packageCode: PACKAGE_A })).toBe(false);
    expect(isMissingCodes(FEE)).toBe(false);
  });

  it('treats a node from an older server, which sends no codes at all, as short of both', () => {
    expect(isMissingCodes({ ...BARE, mxikCode: undefined, packageCode: undefined })).toBe(true);
  });
});

describe('the format guards', () => {
  it('accept exactly seventeen digits for an ИКПУ', () => {
    expect(MXIK_PATTERN.test(MXIK_A)).toBe(true);
    expect(MXIK_PATTERN.test('1070600100100000')).toBe(false);
    expect(MXIK_PATTERN.test('107060010010000000')).toBe(false);
    expect(MXIK_PATTERN.test('1070600100100000A')).toBe(false);
  });

  it('accept a digits-only package code and refuse anything else', () => {
    expect(PACKAGE_CODE_PATTERN.test(PACKAGE_A)).toBe(true);
    expect(PACKAGE_CODE_PATTERN.test('15 00316')).toBe(false);
    expect(PACKAGE_CODE_PATTERN.test('pack-1')).toBe(false);
    expect(PACKAGE_CODE_PATTERN.test('')).toBe(false);
  });
});

describe('FiscalBackfillEditor', () => {
  let fixture: ComponentFixture<FiscalBackfillEditor>;
  let api: { backfillCodes: ReturnType<typeof vi.fn> };
  let reference: { searchMxikReference: ReturnType<typeof vi.fn> };
  let savedCount: number;

  async function render(
    nodes: readonly FiscalCoverageNode[],
    defaults: readonly FiscalCategoryDefault[] = [BURGER_DEFAULT],
  ): Promise<void> {
    fixture.componentRef.setInput('nodes', nodes);
    fixture.componentRef.setInput('categoryDefaults', defaults);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    api = { backfillCodes: vi.fn(async (_scope, items) => applied(items)) };
    // The official list has never been imported: a lookup answers with nothing.
    reference = { searchMxikReference: vi.fn(() => of<readonly MxikReferenceRow[]>([])) };
    await TestBed.configureTestingModule({
      imports: [FiscalBackfillEditor],
      providers: [
        { provide: FiscalizationApi, useValue: api },
        { provide: CatalogApi, useValue: reference },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FiscalBackfillEditor);
    savedCount = 0;
    fixture.componentInstance.saved.subscribe(() => savedCount++);
  });

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement | null {
    return el().querySelector(`[data-testid="${testId}"]`);
  }

  /**
   * The text field behind a cell: the cell itself for a plain input, or the typeahead's field
   * inside the fieldset that carries the ИКПУ cell's test id.
   */
  function input(testId: string): HTMLInputElement {
    const cell = byId(testId) as HTMLElement;
    return cell instanceof HTMLInputElement
      ? cell
      : (cell.querySelector('input') as HTMLInputElement);
  }

  function type(testId: string, value: string): void {
    const target = input(testId);
    target.value = value;
    target.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function paste(testId: string, text: string): Event {
    const event = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'clipboardData', { value: { getData: () => text } });
    input(testId).dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  async function save(): Promise<void> {
    (byId('backfill-save') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();
  }

  function rowIds(): string[] {
    return [...el().querySelectorAll('[data-testid="backfill-row"]')].map(
      (row) => row.getAttribute('data-node-id') ?? '',
    );
  }

  function itemsOfCall(call: number): readonly FiscalBackfillItem[] {
    return api.backfillCodes.mock.calls[call][1] as readonly FiscalBackfillItem[];
  }

  // ------------------------------------------------------------------ the list

  it('lists only the dishes and modifier options still short of a code, with the code an item already holds', async () => {
    await render([BARE, HALF, CODES_ONLY, OPTION, FEE]);

    expect(rowIds()).toEqual(['bare', 'half', 'option']);
    expect(input('backfill-mxik-half').value).toBe(MXIK_B);
    expect(input('backfill-package-half').value).toBe('');
    expect(input('backfill-mxik-bare').value).toBe('');
  });

  it('says so, in the editor itself, that the official list is not imported and codes are checked by format only', async () => {
    await render([BARE]);

    const note = byId('fiscal-backfill-reference-note')?.textContent ?? '';
    expect(note).toContain('not imported yet');
    expect(note).toContain('17 digits');
    expect(note).toContain('a search finds nothing');
  });

  it('shows an empty state when every dish has both codes', async () => {
    await render([CODES_ONLY, FEE]);

    expect(byId('fiscal-backfill-empty')?.textContent).toContain(
      'Every dish and modifier option has an ИКПУ and a package code',
    );
    expect(byId('backfill-save')).toBeNull();
  });

  // ---------------------------------------------------------------- validation

  it('marks a mistyped ИКПУ and a non-numeric package code, and sends neither', async () => {
    await render([BARE, COLA]);

    type('backfill-mxik-bare', '1070600100100000');
    type('backfill-package-bare', 'pack-1');
    type('backfill-mxik-cola', MXIK_A);

    expect(byId('backfill-mxik-error-bare')?.textContent).toContain('exactly 17 digits');
    expect(byId('backfill-package-error-bare')?.textContent).toContain('digits only');
    expect(byId('backfill-mxik-error-cola')).toBeNull();
    expect(input('backfill-mxik-bare').getAttribute('aria-invalid')).toBe('true');
    expect(byId('backfill-invalid')?.textContent).toContain('1 rows');

    await save();

    expect(api.backfillCodes).toHaveBeenCalledTimes(1);
    expect(itemsOfCall(0)).toEqual([
      { nodeType: 'VARIANT', nodeId: 'cola', mxikCode: MXIK_A, packageCode: undefined },
    ]);
  });

  it('strips the spaces a spreadsheet cell arrives with', async () => {
    await render([BARE]);

    type('backfill-mxik-bare', '10706 001 001 000000');

    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
    expect(byId('backfill-mxik-error-bare')).toBeNull();
  });

  // A backfill fills gaps. A cell that already holds a code is shown as it is and
  // cannot be edited: changing or removing a classification is the product
  // editor's, and a paste that lands one row too low must not be able to write a
  // neighbour's valid-looking code over a correct one.
  it('shows a code a dish already holds as read-only, and leaves the empty cell beside it editable', async () => {
    await render([HALF]);

    expect(input('backfill-mxik-half').readOnly).toBe(true);
    expect(input('backfill-mxik-half').getAttribute('aria-readonly')).toBe('true');
    expect(input('backfill-package-half').readOnly).toBe(false);
  });

  it('never makes an edit to a stored code, typed or cleared, sendable', async () => {
    await render([HALF]);

    type('backfill-mxik-half', MXIK_A);
    type('backfill-mxik-half', '');

    expect(byId('backfill-dirty-count')).toBeNull();
    expect(byId('backfill-mxik-error-half')).toBeNull();
    expect((byId('backfill-save') as HTMLButtonElement).disabled).toBe(true);
    await save();
    expect(api.backfillCodes).not.toHaveBeenCalled();
  });

  it('forgets an edit typed back to what the dish already holds', async () => {
    await render([BARE]);

    type('backfill-mxik-bare', MXIK_A);
    expect(byId('backfill-dirty-count')?.textContent).toContain('1 unsaved');

    type('backfill-mxik-bare', '');

    expect(byId('backfill-dirty-count')).toBeNull();
    expect((byId('backfill-save') as HTMLButtonElement).disabled).toBe(true);
  });

  // ---------------------------------------------------------------------- paste

  it('fills down from the cell a multi-line paste lands in', async () => {
    await render([BARE, HALF, COLA]);

    const event = paste('backfill-package-bare', `${PACKAGE_A}\r\n1500175\r\n1500999\r\n`);

    expect(event.defaultPrevented).toBe(true);
    expect(input('backfill-package-bare').value).toBe(PACKAGE_A);
    expect(input('backfill-package-half').value).toBe('1500175');
    expect(input('backfill-package-cola').value).toBe('1500999');
    expect(input('backfill-mxik-bare').value).toBe('');
  });

  it('spreads a tab-separated pair over the ИКПУ and package columns, row by row', async () => {
    await render([BARE, COLA]);

    paste('backfill-mxik-bare', `${MXIK_A}\t${PACKAGE_A}\n${MXIK_B}\t1500175`);

    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
    expect(input('backfill-package-bare').value).toBe(PACKAGE_A);
    expect(input('backfill-mxik-cola').value).toBe(MXIK_B);
    expect(input('backfill-package-cola').value).toBe('1500175');
  });

  it('ignores a paste that runs past the last row rather than inventing rows', async () => {
    await render([BARE]);

    paste('backfill-package-bare', '1500316\n1500175\n1500999');

    expect(rowIds()).toEqual(['bare']);
    expect(input('backfill-package-bare').value).toBe('1500316');
  });

  it('leaves a single pasted value to the browser', async () => {
    await render([BARE]);

    const event = paste('backfill-mxik-bare', MXIK_A);

    expect(event.defaultPrevented).toBe(false);
  });

  it('flags a pasted cell in the wrong format instead of accepting it', async () => {
    await render([BARE, COLA]);

    paste('backfill-mxik-bare', `${MXIK_A}\nnot-a-code`);

    expect(byId('backfill-mxik-error-bare')).toBeNull();
    expect(byId('backfill-mxik-error-cola')?.textContent).toContain('exactly 17 digits');
  });

  it('fills a pasted column down around a stored code instead of writing over it', async () => {
    await render([BARE, HALF, COLA]);

    paste('backfill-mxik-bare', `${MXIK_A}\n${MXIK_A}\n${MXIK_A}`);

    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
    expect(input('backfill-mxik-half').value).toBe(MXIK_B);
    expect(input('backfill-mxik-cola').value).toBe(MXIK_A);
    expect(byId('backfill-dirty-count')?.textContent).toContain('2 unsaved');
    expect(byId('backfill-paste-skipped')?.textContent).toContain('Pasted codes left out: 1');

    await save();

    expect(itemsOfCall(0)).toEqual([
      { nodeType: 'VARIANT', nodeId: 'bare', mxikCode: MXIK_A, packageCode: undefined },
      { nodeType: 'VARIANT', nodeId: 'cola', mxikCode: MXIK_A, packageCode: undefined },
    ]);
  });

  it('fills only the empty half of a two-column paste that meets a half-classified dish', async () => {
    await render([HALF]);

    paste('backfill-mxik-half', `${MXIK_A}\t${PACKAGE_A}\n`);
    await save();

    expect(itemsOfCall(0)).toEqual([
      { nodeType: 'VARIANT', nodeId: 'half', mxikCode: undefined, packageCode: PACKAGE_A },
    ]);
  });

  it('says nothing about a pasted cell that only repeats the stored code', async () => {
    await render([BARE, HALF]);

    paste('backfill-mxik-bare', `${MXIK_A}\n${MXIK_B}`);

    expect(byId('backfill-paste-skipped')).toBeNull();
  });

  // ------------------------------------------------------- copy category default

  it('copies the category default into a dish’s empty cells, for the operator to review and save', async () => {
    await render([BARE]);

    (byId('backfill-copy-bare') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
    expect(input('backfill-package-bare').value).toBe(PACKAGE_A);
    expect(api.backfillCodes).not.toHaveBeenCalled();
    expect((byId('backfill-copy-bare') as HTMLButtonElement).disabled).toBe(true);
  });

  it('names where the default comes from in the action’s title', async () => {
    await render([BARE]);

    const title = byId('backfill-copy-bare')?.getAttribute('title') ?? '';

    expect(title).toContain('2 of 3');
    expect(title).toContain('Burgers');
    expect(title).toContain(MXIK_A);
  });

  it('offers nothing for a dish whose category has no default, or that sits in no category', async () => {
    await render([COLA, LOOSE]);

    expect((byId('backfill-copy-cola') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('backfill-copy-loose') as HTMLButtonElement).disabled).toBe(true);
    expect(byId('backfill-copy-cola')?.getAttribute('title')).toContain('Nothing to copy');
  });

  it('does not put a default beside a different ИКПУ the dish already holds', async () => {
    await render([HALF]);

    // HALF holds MXIK_B; the category default is MXIK_A with its own package code.
    expect((byId('backfill-copy-half') as HTMLButtonElement).disabled).toBe(true);
  });

  it('completes a dish whose ИКПУ already matches the default, filling only the package code', async () => {
    await render([variant('match', { mxikCode: MXIK_A })]);

    (byId('backfill-copy-match') as HTMLButtonElement).click();
    fixture.detectChanges();
    await save();

    expect(itemsOfCall(0)).toEqual([
      { nodeType: 'VARIANT', nodeId: 'match', mxikCode: undefined, packageCode: PACKAGE_A },
    ]);
  });

  it('copies every applicable default at once and counts them', async () => {
    await render([BARE, HALF, COLA, variant('second')]);

    expect(byId('backfill-copy-all')?.textContent).toContain('(2)');
    (byId('backfill-copy-all') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
    expect(input('backfill-mxik-second').value).toBe(MXIK_A);
    expect(input('backfill-mxik-half').value).toBe(MXIK_B);
    expect(input('backfill-mxik-cola').value).toBe('');
    expect(byId('backfill-dirty-count')?.textContent).toContain('2 unsaved');
  });

  it('gives no copy action against an older server, which sends no category defaults', async () => {
    await render([BARE], []);

    expect((byId('backfill-copy-bare') as HTMLButtonElement).disabled).toBe(true);
    expect((byId('backfill-copy-all') as HTMLButtonElement).disabled).toBe(true);
  });

  // --------------------------------------------------------------------- saving

  it('sends a package-code-only row without an ИКПУ, so the platform keeps the one the dish holds', async () => {
    await render([HALF]);

    type('backfill-package-half', PACKAGE_A);
    await save();

    expect(api.backfillCodes).toHaveBeenCalledWith(SCOPE, [
      { nodeType: 'VARIANT', nodeId: 'half', mxikCode: undefined, packageCode: PACKAGE_A },
    ]);
  });

  it('goes out in batches, each row exactly once, and reports the totals', async () => {
    const many = Array.from({ length: 250 }, (_, index) => variant(`dish-${index}`));
    await render(many);

    paste('backfill-mxik-dish-0', many.map(() => MXIK_A).join('\n'));
    await save();

    expect(api.backfillCodes).toHaveBeenCalledTimes(3);
    expect([0, 1, 2].map((call) => itemsOfCall(call).length)).toEqual([
      BACKFILL_BATCH_SIZE,
      BACKFILL_BATCH_SIZE,
      50,
    ]);
    const sent = [0, 1, 2].flatMap((call) => itemsOfCall(call).map((item) => item.nodeId));
    expect(new Set(sent).size).toBe(250);
    expect(byId('backfill-summary')?.textContent).toContain('Saved 250');
    expect(savedCount).toBe(1);
  });

  it('keeps a row the platform reports missing, marks it, and clears the rows it applied', async () => {
    api.backfillCodes.mockImplementation(async (_scope, items: readonly FiscalBackfillItem[]) =>
      items.map((item) => ({
        nodeType: 'VARIANT' as const,
        nodeId: item.nodeId,
        status: item.nodeId === 'cola' ? ('NOT_FOUND' as const) : ('CLASSIFIED' as const),
      })),
    );
    await render([BARE, COLA]);

    type('backfill-mxik-bare', MXIK_A);
    type('backfill-mxik-cola', MXIK_B);
    await save();

    expect(byId('backfill-problem-cola')?.textContent).toContain('no longer exists');
    expect(input('backfill-mxik-cola').value).toBe(MXIK_B);
    expect(byId('backfill-problem-bare')).toBeNull();
    expect(byId('backfill-summary')?.textContent).toContain('Saved 1');
    expect(byId('backfill-summary')?.textContent).toContain('not saved 1');
    expect(savedCount).toBe(1);
  });

  it('marks a row the platform refuses to overwrite, keeps the rest saved, and tells the page to reload', async () => {
    api.backfillCodes.mockImplementation(async (_scope, items: readonly FiscalBackfillItem[]) =>
      items.map((item) => ({
        nodeType: 'VARIANT' as const,
        nodeId: item.nodeId,
        status: item.nodeId === 'cola' ? ('CONFLICT' as const) : ('CLASSIFIED' as const),
      })),
    );
    await render([BARE, COLA]);

    type('backfill-mxik-bare', MXIK_A);
    type('backfill-mxik-cola', MXIK_B);
    await save();

    expect(byId('backfill-problem-cola')?.textContent).toContain('already holds a code');
    expect(byId('backfill-problem-bare')).toBeNull();
    expect(byId('backfill-summary')?.textContent).toContain('Saved 1');
    expect(byId('backfill-summary')?.textContent).toContain('not saved 1');
    expect(savedCount).toBe(1);
  });

  it('reloads the page when the only outcome is a conflict, because the list it showed is stale', async () => {
    api.backfillCodes.mockImplementation(async (_scope, items: readonly FiscalBackfillItem[]) =>
      items.map((item) => ({
        nodeType: 'VARIANT' as const,
        nodeId: item.nodeId,
        status: 'CONFLICT' as const,
      })),
    );
    await render([BARE]);

    type('backfill-mxik-bare', MXIK_A);
    await save();

    expect(savedCount).toBe(1);
  });

  it('drops a pending edit once a reload shows the dish now holds that code', async () => {
    await render([BARE]);

    type('backfill-mxik-bare', MXIK_A);
    fixture.componentRef.setInput('nodes', [variant('bare', { mxikCode: MXIK_B })]);
    fixture.detectChanges();

    expect(input('backfill-mxik-bare').value).toBe(MXIK_B);
    expect(input('backfill-mxik-bare').readOnly).toBe(true);
    expect(byId('backfill-dirty-count')).toBeNull();
  });

  it('counts a row the platform found already set, and does not treat it as a failure', async () => {
    api.backfillCodes.mockImplementation(async (_scope, items: readonly FiscalBackfillItem[]) =>
      items.map((item) => ({
        nodeType: 'VARIANT' as const,
        nodeId: item.nodeId,
        status: 'UNCHANGED' as const,
      })),
    );
    await render([HALF]);

    type('backfill-package-half', PACKAGE_A);
    await save();

    expect(byId('backfill-summary')?.textContent).toContain('already set 1');
    expect(byId('backfill-problem-half')).toBeNull();
  });

  it('marks the rows of a batch whose request failed, keeps their edits, and still sends the later batches', async () => {
    const many = Array.from({ length: BACKFILL_BATCH_SIZE + 2 }, (_, index) =>
      variant(`dish-${index}`),
    );
    api.backfillCodes
      .mockRejectedValueOnce(new ApiError('INTERNAL', 500, null, 'corr-1'))
      .mockImplementation(async (_scope, items) => applied(items));
    await render(many);

    paste('backfill-mxik-dish-0', many.map(() => MXIK_A).join('\n'));
    await save();

    expect(api.backfillCodes).toHaveBeenCalledTimes(2);
    expect(byId('backfill-problem-dish-0')?.textContent).toContain('Not saved');
    expect(input('backfill-mxik-dish-0').value).toBe(MXIK_A);
    expect(byId(`backfill-problem-dish-${BACKFILL_BATCH_SIZE}`)).toBeNull();
    expect(byId('backfill-save-error')).not.toBeNull();
    expect(byId('backfill-summary')?.textContent).toContain(`not saved ${BACKFILL_BATCH_SIZE}`);
  });

  it('does not tell the page to reload when nothing was applied', async () => {
    api.backfillCodes.mockRejectedValue(new ApiError('INTERNAL', 500, null, 'corr-1'));
    await render([BARE]);

    type('backfill-mxik-bare', MXIK_A);
    await save();

    expect(savedCount).toBe(0);
    expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
  });

  it('drops every pending edit on discard', async () => {
    await render([BARE, COLA]);

    type('backfill-mxik-bare', MXIK_A);
    type('backfill-mxik-cola', MXIK_B);
    (byId('backfill-discard') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(input('backfill-mxik-bare').value).toBe('');
    expect(input('backfill-mxik-cola').value).toBe('');
    expect(api.backfillCodes).not.toHaveBeenCalled();
  });

  it('follows a reload: a saved dish leaves the list once the coverage read says it has both codes', async () => {
    await render([BARE, COLA]);

    type('backfill-mxik-bare', MXIK_A);
    type('backfill-package-bare', PACKAGE_A);
    await save();
    fixture.componentRef.setInput('nodes', [
      COLA,
      variant('bare', { mxikCode: MXIK_A, packageCode: PACKAGE_A }),
    ]);
    fixture.detectChanges();

    expect(rowIds()).toEqual(['cola']);
  });

  // -------------------------------------------------- modifier options (row 10.7c)

  describe('modifier options', () => {
    const CHEESE: FiscalCoverageNode = {
      nodeType: 'MODIFIER_OPTION',
      nodeId: 'cheese',
      name: 'Cheese',
      categoryName: null,
      locationCount: 0,
      categoryId: null,
      mxikCode: null,
      packageCode: null,
    };

    it('lists an option beside the dishes and says which kind each row is', async () => {
      await render([BARE, CHEESE]);

      const kinds = [...el().querySelectorAll('[data-testid="backfill-row"] td:first-child')].map(
        (cell) => cell.textContent?.trim(),
      );
      expect(kinds).toEqual(['Dish', 'Modifier']);
    });

    it('sends an option with its own node type, in the same batch as a dish', async () => {
      await render([BARE, CHEESE]);

      type('backfill-mxik-bare', MXIK_A);
      type('backfill-package-cheese', PACKAGE_A);
      type('backfill-mxik-cheese', MXIK_B);
      await save();

      expect(api.backfillCodes).toHaveBeenCalledTimes(1);
      expect(itemsOfCall(0)).toEqual([
        { nodeType: 'VARIANT', nodeId: 'bare', mxikCode: MXIK_A, packageCode: undefined },
        { nodeType: 'MODIFIER_OPTION', nodeId: 'cheese', mxikCode: MXIK_B, packageCode: PACKAGE_A },
      ]);
      expect(byId('backfill-summary')?.textContent).toContain('Saved 2');
    });

    it('offers an option no category default, whatever the other rows are offered', async () => {
      await render([BARE, CHEESE]);

      expect((byId('backfill-copy-bare') as HTMLButtonElement).disabled).toBe(false);
      expect((byId('backfill-copy-cheese') as HTMLButtonElement).disabled).toBe(true);
      (byId('backfill-copy-all') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(input('backfill-mxik-cheese').value).toBe('');
    });

    it('keeps the code an option already holds, and fills the empty cell beside it', async () => {
      await render([{ ...CHEESE, mxikCode: MXIK_B }]);

      expect(input('backfill-mxik-cheese').readOnly).toBe(true);
      type('backfill-package-cheese', PACKAGE_A);
      await save();

      expect(itemsOfCall(0)).toEqual([
        {
          nodeType: 'MODIFIER_OPTION',
          nodeId: 'cheese',
          mxikCode: undefined,
          packageCode: PACKAGE_A,
        },
      ]);
    });
  });

  // ----------------------------------------------- the ИКПУ typeahead (row 4.2e)

  describe('the ИКПУ typeahead', () => {
    const MILK: MxikReferenceRow = {
      code: MXIK_A,
      labelRu: 'Молоко',
      labelUz: 'Sut',
      labelEn: 'Milk',
      defaultPackageCodes: [PACKAGE_A],
      validFrom: '2024-01-01',
    };
    const BREAD: MxikReferenceRow = {
      code: MXIK_B,
      labelRu: 'Хлеб',
      labelUz: 'Non',
      labelEn: 'Bread',
      defaultPackageCodes: ['1500175', '1500999'],
      validFrom: '2024-01-01',
    };
    const PLAIN: MxikReferenceRow = { ...MILK, code: '10202002002000000', defaultPackageCodes: [] };

    afterEach(() => vi.useRealTimers());

    /** Types a name into a row’s ИКПУ cell and lets the debounced lookup answer. */
    async function lookUp(
      nodeId: string,
      text: string,
      rows: readonly MxikReferenceRow[],
    ): Promise<void> {
      reference.searchMxikReference.mockReturnValue(of(rows));
      vi.useFakeTimers();
      type(`backfill-mxik-${nodeId}`, text);
      await vi.advanceTimersByTimeAsync(300);
      fixture.detectChanges();
      vi.useRealTimers();
    }

    function choose(nodeId: string, index: number): void {
      (
        el().querySelectorAll(
          `[data-testid="backfill-mxik-${nodeId}"] [data-testid="q-combobox-option"]`,
        )[index] as HTMLElement
      ).click();
      fixture.detectChanges();
    }

    it('searches the reference in the brand’s scope and writes nothing while a name is being typed', async () => {
      await render([BARE]);

      await lookUp('bare', 'milk', [MILK]);

      expect(reference.searchMxikReference).toHaveBeenCalledWith(SCOPE, 'milk');
      expect(byId('backfill-dirty-count')).toBeNull();
      expect((byId('backfill-save') as HTMLButtonElement).disabled).toBe(true);
    });

    it('does not flag a name as a badly formed code', async () => {
      await render([BARE]);

      await lookUp('bare', 'milk', [MILK]);

      expect(byId('backfill-mxik-error-bare')).toBeNull();
      expect(input('backfill-mxik-bare').getAttribute('aria-invalid')).not.toBe('true');
    });

    it('writes the chosen code, and fills the one package code the reference lists for it', async () => {
      await render([BARE]);
      await lookUp('bare', 'milk', [MILK]);

      choose('bare', 0);

      expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
      expect(input('backfill-package-bare').value).toBe(PACKAGE_A);
      await save();
      expect(itemsOfCall(0)).toEqual([
        { nodeType: 'VARIANT', nodeId: 'bare', mxikCode: MXIK_A, packageCode: PACKAGE_A },
      ]);
    });

    it('offers several package codes as a choice, fills none, and fills the one that is clicked', async () => {
      await render([BARE]);
      await lookUp('bare', 'bread', [BREAD]);

      choose('bare', 0);

      expect(input('backfill-package-bare').value).toBe('');
      expect(byId('backfill-package-suggestions-bare')?.textContent).toContain('1500175');
      expect(byId('backfill-package-suggestions-bare')?.textContent).toContain('1500999');

      (byId('backfill-package-suggestion-bare-1500999') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(input('backfill-package-bare').value).toBe('1500999');
      expect(byId('backfill-package-suggestion-bare-1500999')).toBeNull();
      expect(byId('backfill-package-suggestion-bare-1500175')).not.toBeNull();
    });

    it('leaves the package cell to be typed when the reference lists no package code', async () => {
      await render([BARE]);
      await lookUp('bare', 'plain', [PLAIN]);

      choose('bare', 0);

      expect(input('backfill-mxik-bare').value).toBe(PLAIN.code);
      expect(input('backfill-package-bare').value).toBe('');
      expect(byId('backfill-package-suggestions-bare')).toBeNull();
    });

    it('does not write over a package code the operator already typed', async () => {
      await render([BARE]);
      type('backfill-package-bare', '1500777');
      await lookUp('bare', 'milk', [MILK]);

      choose('bare', 0);

      expect(input('backfill-package-bare').value).toBe('1500777');
    });

    it('does not touch a package code the item already holds, or suggest over it', async () => {
      await render([variant('pkg-only', { packageCode: '1500777' })]);
      await lookUp('pkg-only', 'bread', [BREAD]);

      choose('pkg-only', 0);

      expect(input('backfill-package-pkg-only').value).toBe('1500777');
      expect(input('backfill-package-pkg-only').readOnly).toBe(true);
      expect(byId('backfill-package-suggestions-pkg-only')).toBeNull();
    });

    it('withdraws the suggestions once the code in the cell is no longer the picked one', async () => {
      await render([BARE]);
      await lookUp('bare', 'bread', [BREAD]);
      choose('bare', 0);
      expect(byId('backfill-package-suggestions-bare')).not.toBeNull();

      type('backfill-mxik-bare', MXIK_A);

      expect(byId('backfill-package-suggestions-bare')).toBeNull();
    });

    it('still takes a typed code when the reference has nothing, as it did before there was a lookup', async () => {
      await render([BARE]);
      await lookUp('bare', MXIK_A, []);

      expect(input('backfill-mxik-bare').value).toBe(MXIK_A);
      await save();

      expect(itemsOfCall(0)).toEqual([
        { nodeType: 'VARIANT', nodeId: 'bare', mxikCode: MXIK_A, packageCode: undefined },
      ]);
    });

    it('drops a half-typed name and a picked row when the edits are discarded', async () => {
      await render([BARE, COLA]);
      await lookUp('bare', 'bread', [BREAD]);
      choose('bare', 0);
      await lookUp('cola', 'cola drink', []);

      (byId('backfill-discard') as HTMLButtonElement | null)?.click();
      fixture.detectChanges();

      expect(input('backfill-mxik-bare').value).toBe('');
      expect(input('backfill-mxik-cola').value).toBe('');
      expect(byId('backfill-package-suggestions-bare')).toBeNull();
    });

    it('is disabled while a save is in flight', async () => {
      let release: (outcomes: FiscalBackfillOutcome[]) => void = () => undefined;
      api.backfillCodes.mockImplementation(
        () => new Promise<FiscalBackfillOutcome[]>((resolve) => (release = resolve)),
      );
      await render([BARE]);
      type('backfill-mxik-bare', MXIK_A);

      (byId('backfill-save') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(input('backfill-mxik-bare').matches(':disabled')).toBe(true);
      release([{ nodeType: 'VARIANT', nodeId: 'bare', status: 'CLASSIFIED' }]);
      await flush();
    });
  });
});
