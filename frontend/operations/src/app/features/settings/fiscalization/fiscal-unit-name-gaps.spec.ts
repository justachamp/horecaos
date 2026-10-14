import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { CatalogApi } from '../../catalog/catalog-api';
import { MxikReferenceRow } from '../../catalog/catalog-domain';
import { FiscalBackfillItem, FiscalBackfillOutcome, FiscalCoverageNode } from './fiscalization-api';
import { FiscalizationApi } from './fiscalization-api';
import { FiscalUnitNameGaps, lacksUnitOrName } from './fiscal-unit-name-gaps';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };
const MXIK_A = '10706001001000000';
const MXIK_B = '10101001001000000';
const PACKAGE_A = '1500316';

function node(id: string, overrides: Partial<FiscalCoverageNode> = {}): FiscalCoverageNode {
  return {
    nodeType: 'VARIANT',
    nodeId: id,
    name: `Dish ${id}`,
    categoryName: 'Burgers',
    locationCount: 2,
    categoryId: 'cat-burgers',
    mxikCode: MXIK_A,
    packageCode: PACKAGE_A,
    fiscalUnitCode: null,
    fiscalName: null,
    ...overrides,
  };
}

/** Holds both codes, lacks the unit and the receipt name. */
const NEITHER = node('neither');
/** Holds both codes and a name; lacks the unit. */
const NO_UNIT = node('no-unit', { fiscalName: 'Burger' });
/** Holds both codes and a unit; lacks the name. */
const NO_NAME = node('no-name', { fiscalUnitCode: 15, mxikCode: MXIK_B });
/** Fully classified: not this section's. */
const COMPLETE = node('complete', { fiscalUnitCode: 15, fiscalName: 'Cola' });
/** Lacks a code: the first editor's. */
const NO_CODE = node('no-code', { mxikCode: null });

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function reference(code: string, units: readonly number[]): MxikReferenceRow {
  return {
    code,
    labelRu: 'x',
    labelUz: 'x',
    defaultPackageCodes: [],
    defaultUnitCodes: units,
    validFrom: '2024-01-01',
  };
}

describe('lacksUnitOrName', () => {
  it('is true for an item holding both codes and lacking the unit or the receipt name', () => {
    expect(lacksUnitOrName(NEITHER)).toBe(true);
    expect(lacksUnitOrName(NO_UNIT)).toBe(true);
    expect(lacksUnitOrName(NO_NAME)).toBe(true);
    expect(lacksUnitOrName(node('blank-name', { fiscalUnitCode: 15, fiscalName: '' }))).toBe(true);
  });

  it('is false for a complete item, one short of a code, the fee and an older server that does not say', () => {
    expect(lacksUnitOrName(COMPLETE)).toBe(false);
    expect(lacksUnitOrName(NO_CODE)).toBe(false);
    expect(lacksUnitOrName(node('fee', { nodeType: 'FEE' }))).toBe(false);
    expect(lacksUnitOrName(node('old', { fiscalUnitCode: undefined, fiscalName: undefined }))).toBe(
      false,
    );
  });
});

describe('FiscalUnitNameGaps', () => {
  let fixture: ComponentFixture<FiscalUnitNameGaps>;
  let api: { backfillCodes: ReturnType<typeof vi.fn> };
  let catalog: { searchMxikReference: ReturnType<typeof vi.fn> };
  let savedCount: number;

  async function render(nodes: readonly FiscalCoverageNode[]): Promise<void> {
    fixture.componentRef.setInput('nodes', nodes);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function byId(testId: string): HTMLElement | null {
    return el().querySelector(`[data-testid="${testId}"]`);
  }

  function field(testId: string): HTMLInputElement {
    return byId(testId) as HTMLInputElement;
  }

  function type(testId: string, value: string): void {
    const target = field(testId);
    target.value = value;
    target.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function click(testId: string): void {
    (byId(testId) as HTMLElement).click();
    fixture.detectChanges();
  }

  function rowIds(): string[] {
    return [...el().querySelectorAll('[data-testid="gaps-row"]')].map(
      (row) => row.getAttribute('data-node-id') ?? '',
    );
  }

  function itemsOfCall(call: number): readonly FiscalBackfillItem[] {
    return api.backfillCodes.mock.calls[call][1] as readonly FiscalBackfillItem[];
  }

  async function save(): Promise<void> {
    click('gaps-save');
    await flush();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    api = {
      backfillCodes: vi.fn(async (_scope, items: readonly FiscalBackfillItem[]) =>
        items.map((item): FiscalBackfillOutcome => ({
          nodeType: item.nodeType,
          nodeId: item.nodeId,
          status: 'CLASSIFIED',
        })),
      ),
    };
    catalog = { searchMxikReference: vi.fn(() => of<readonly MxikReferenceRow[]>([])) };
    await TestBed.configureTestingModule({
      imports: [FiscalUnitNameGaps],
      providers: [
        provideRouter([]),
        { provide: FiscalizationApi, useValue: api },
        { provide: CatalogApi, useValue: catalog },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FiscalUnitNameGaps);
    savedCount = 0;
    fixture.componentInstance.saved.subscribe(() => savedCount++);
  });

  it('lists exactly the items that hold both codes and lack a unit or a receipt name, and nothing else', async () => {
    await render([NEITHER, NO_UNIT, NO_NAME, COMPLETE, NO_CODE]);

    expect(rowIds()).toEqual(['neither', 'no-unit', 'no-name']);
    expect(byId('fiscal-gaps')?.textContent).toContain('3 items hold an ИКПУ and a package code');
  });

  it('draws nothing when no item is short of either field', async () => {
    await render([COMPLETE, NO_CODE]);

    expect(byId('fiscal-gaps')).toBeNull();
  });

  it('shows what an item already holds as read-only, and offers to fill only what is missing', async () => {
    await render([NO_UNIT, NO_NAME]);

    expect(field('gaps-name-no-unit').value).toBe('Burger');
    expect(field('gaps-name-no-unit').readOnly).toBe(true);
    expect(field('gaps-unit-no-unit').readOnly).toBe(false);
    expect(field('gaps-unit-no-name').value).toBe('15');
    expect(field('gaps-unit-no-name').readOnly).toBe(true);
    expect(byId('gaps-reference-unit-no-name')).toBeNull();
    expect(byId('gaps-use-name-no-unit')).toBeNull();
    expect(byId('gaps-use-name-no-name')).not.toBeNull();
  });

  it('hands the rest to the catalog’s fiscal workbench with a link that opens it', async () => {
    await render([NEITHER]);

    const link = byId('gaps-workbench') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/catalog/products?workbench=fiscal');
  });

  it('sends a typed unit and a typed receipt name in the same MERGE batch that carries the codes, and reloads', async () => {
    await render([NEITHER, NO_UNIT]);

    type('gaps-unit-neither', '15');
    type('gaps-name-neither', ' Burger classic ');
    type('gaps-unit-no-unit', '16');
    await save();

    expect(api.backfillCodes).toHaveBeenCalledTimes(1);
    expect(itemsOfCall(0)).toEqual([
      { nodeType: 'VARIANT', nodeId: 'neither', fiscalUnitCode: 15, fiscalName: 'Burger classic' },
      { nodeType: 'VARIANT', nodeId: 'no-unit', fiscalUnitCode: 16, fiscalName: undefined },
    ]);
    expect(byId('gaps-summary')?.textContent).toContain('Saved 2');
    expect(savedCount).toBe(1);
  });

  it('refuses a unit that is not a positive whole number and a blank receipt name, and sends neither', async () => {
    await render([NEITHER, NO_UNIT]);

    type('gaps-unit-neither', '0');
    type('gaps-name-neither', '   ');
    type('gaps-unit-no-unit', '16');

    expect(byId('gaps-unit-error-neither')?.textContent).toContain('positive whole number');
    expect(byId('gaps-name-error-neither')?.textContent).toContain('1 to 63');
    expect(byId('gaps-invalid')?.textContent).toContain('1 rows');
    await save();

    expect(itemsOfCall(0).map((item) => item.nodeId)).toEqual(['no-unit']);
  });

  it('offers the item’s own name cut to the receipt’s 63 characters, and fills no name without a click', async () => {
    const long = `${'Very long dish name '.repeat(5)}end`;
    await render([node('long', { name: long })]);
    expect(field('gaps-name-long').value).toBe('');

    click('gaps-use-name-long');

    const value = field('gaps-name-long').value;
    expect(value.length).toBeLessThanOrEqual(63);
    expect(long.startsWith(value)).toBe(true);
    await save();
    expect(itemsOfCall(0)[0].fiscalName).toBe(value);
  });

  it('fills every empty receipt name from the item names in one click', async () => {
    await render([NEITHER, NO_UNIT, NO_NAME]);

    click('gaps-fill-names');

    expect(field('gaps-name-neither').value).toBe('Dish neither');
    expect(field('gaps-name-no-name').value).toBe('Dish no-name');
    expect(field('gaps-name-no-unit').value).toBe('Burger');
  });

  it('takes the unit from the ИКПУ list when it names exactly one, in the brand’s scope', async () => {
    catalog.searchMxikReference.mockReturnValue(of([reference(MXIK_A, [1500])]));
    await render([NEITHER]);

    click('gaps-reference-unit-neither');
    await flush();
    fixture.detectChanges();

    expect(catalog.searchMxikReference).toHaveBeenCalledWith(SCOPE, MXIK_A);
    expect(field('gaps-unit-neither').value).toBe('1500');
  });

  it('offers the units when the list names several, and says so when it names none', async () => {
    catalog.searchMxikReference.mockImplementation((_scope: unknown, code: string) =>
      of([reference(code, code === MXIK_A ? [1500, 1501] : [])]),
    );
    await render([NEITHER, NO_NAME, node('other', { mxikCode: MXIK_B })]);

    click('gaps-reference-unit-neither');
    await flush();
    fixture.detectChanges();
    expect(field('gaps-unit-neither').value).toBe('');
    expect(byId('gaps-unit-choices-neither')).not.toBeNull();
    click('gaps-unit-choice-neither-1501');
    expect(field('gaps-unit-neither').value).toBe('1501');

    click('gaps-reference-unit-other');
    await flush();
    fixture.detectChanges();
    expect(byId('gaps-reference-none-other')).not.toBeNull();
  });

  it('says the list could not be read, and writes nothing, when the lookup fails', async () => {
    catalog.searchMxikReference.mockReturnValue(throwError(() => new Error('down')));
    await render([NEITHER]);

    click('gaps-reference-unit-neither');
    await flush();
    fixture.detectChanges();

    expect(byId('gaps-reference-unavailable-neither')).not.toBeNull();
    expect(field('gaps-unit-neither').value).toBe('');
  });

  it('fills the empty units of every item from the list with one lookup per ИКПУ, and counts what it could not', async () => {
    catalog.searchMxikReference.mockImplementation((_scope: unknown, code: string) =>
      of([reference(code, code === MXIK_A ? [1500] : [1500, 1501])]),
    );
    await render([NEITHER, NO_UNIT, node('other', { mxikCode: MXIK_B })]);

    click('gaps-fill-units');
    await flush();
    fixture.detectChanges();

    expect(catalog.searchMxikReference).toHaveBeenCalledTimes(2);
    expect(field('gaps-unit-neither').value).toBe('1500');
    expect(field('gaps-unit-no-unit').value).toBe('1500');
    expect(field('gaps-unit-other').value).toBe('');
    expect(byId('gaps-fill-summary')?.textContent).toContain('Filled 2 units');
    expect(byId('gaps-fill-summary')?.textContent).toContain('1 still need one');
  });

  it('keeps the edit and marks the row when the platform met a different value, and reloads the page', async () => {
    api.backfillCodes.mockResolvedValue([
      { nodeType: 'VARIANT', nodeId: 'neither', status: 'CONFLICT' },
    ]);
    await render([NEITHER]);
    type('gaps-unit-neither', '15');

    await save();

    expect(byId('gaps-problem-neither')?.textContent).toContain('differs from yours');
    expect(field('gaps-unit-neither').value).toBe('15');
    expect(savedCount).toBe(1);
  });

  it('discards the unsaved edits', async () => {
    await render([NEITHER]);
    type('gaps-unit-neither', '15');
    expect(byId('gaps-dirty-count')?.textContent).toContain('1 unsaved');

    click('gaps-discard');

    expect(field('gaps-unit-neither').value).toBe('');
    expect(byId('gaps-dirty-count')).toBeNull();
  });
});
