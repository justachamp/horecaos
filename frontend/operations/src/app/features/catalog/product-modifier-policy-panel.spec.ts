import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import {
  AttachedModifierGroup,
  ModifierGroupSummary,
  ProductDetail,
  VariantDetail,
} from './catalog-domain';
import { CompositeApi, ModifierAttachmentView } from './composite-api';
import { ProductModifierPolicyPanel } from './product-modifier-policy-panel';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

const GROUPS: readonly ModifierGroupSummary[] = [
  {
    groupId: 'box',
    code: 'BOX',
    name: 'Delivery box',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameOptionMultipleTimes: false,
    optionCount: 1,
    status: 'ACTIVE',
  },
  {
    groupId: 'sauces',
    code: 'SAUCES',
    name: 'Sauces',
    required: false,
    minimumSelections: 0,
    maximumSelections: 3,
    allowSameOptionMultipleTimes: false,
    optionCount: 4,
    status: 'ACTIVE',
  },
];

const variant: VariantDetail = {
  variantId: 'v1',
  sku: 'SALAD-1',
  unitCode: 'PIECE',
  isDefault: true,
  sortOrder: 0,
  status: 'ACTIVE',
  version: 1,
  translations: { en: { name: 'Salad' } },
  fiscal: null,
};

function product(modifierGroups: readonly AttachedModifierGroup[]): ProductDetail {
  return {
    productId: 'p1',
    code: 'SALAD',
    status: 'ACTIVE',
    version: 1,
    translations: { en: { name: 'Salad' } },
    catalogIds: ['c1'],
    categoryIds: [],
    variants: [variant],
    modifierGroups,
    media: [],
  };
}

const attachment = (groupId: string): ModifierAttachmentView => ({
  ownerId: 'v1',
  ownerType: 'VARIANT',
  modifierGroupId: groupId,
  sortOrder: 0,
  visibility: 'VISIBLE',
  version: 1,
});

function render(
  groups: readonly AttachedModifierGroup[],
  composite: Record<string, unknown> = {},
  variantAttachments: readonly ModifierAttachmentView[] = [],
) {
  TestBed.resetTestingModule();
  const api = {
    variantAttachments: vi.fn().mockReturnValue(of(variantAttachments)),
    setProductAttachmentPolicy: vi.fn().mockReturnValue(of({})),
    attachToVariant: vi.fn().mockReturnValue(of(attachment('sauces'))),
    ...composite,
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: CompositeApi, useValue: api },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ProductModifierPolicyPanel);
  fixture.componentRef.setInput('product', product(groups));
  fixture.componentRef.setInput('library', GROUPS);
  fixture.componentRef.setInput('locale', 'en');
  let changed = 0;
  fixture.componentInstance.changed.subscribe(() => changed++);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string, within: ParentNode = host) =>
    within.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const settle = async () => {
    // Zoneless: `whenStable` does not wait for the component's own promises, so let the
    // macrotask queue drain a few times with a render between.
    for (let pass = 0; pass < 3; pass++) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      fixture.detectChanges();
    }
  };
  const choose = (select: HTMLSelectElement, value: string) => {
    select.value = value;
    select.dispatchEvent(new Event('change'));
  };
  return { fixture, host, q, settle, choose, api, changed: () => changed };
}

describe('ProductModifierPolicyPanel', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('lists each attached group with the group’s own values, read-only', () => {
    const { host, q } = render([{ groupId: 'sauces', sortOrder: 0 }]);

    expect(q('policy-group-name')?.textContent).toContain('Sauces');
    expect(q('policy-group-values')?.textContent).toContain('optional, at least 0, at most 3');
    expect(host.querySelectorAll('[data-testid="policy-group"]')).toHaveLength(1);
  });

  it('shows no modes and no hidden chip for a group the customer chooses from', () => {
    const { q } = render([{ groupId: 'sauces', sortOrder: 0 }]);

    expect(q('policy-modes')).toBeNull();
    expect(q('policy-hidden-chip')).toBeNull();
  });

  it('turns a group hidden: shows the order types, defaulting to every one, and the publish rule', async () => {
    const { q, settle, choose } = render([{ groupId: 'sauces', sortOrder: 0 }]);

    choose(q('policy-visibility') as HTMLSelectElement, 'HIDDEN_AUTO_SELECT');
    await settle();

    for (const mode of ['DELIVERY', 'PICKUP', 'DINE_IN']) {
      expect((q(`policy-mode-${mode}`) as HTMLInputElement).checked).toBe(true);
    }
    expect(q('policy-hidden-chip')).not.toBeNull();
    expect(q('policy-ambiguous')?.textContent).toContain('exactly one active option');
  });

  it('does not warn about a hidden group that is required with exactly one option', () => {
    const { q } = render([
      {
        groupId: 'box',
        sortOrder: 0,
        visibility: 'HIDDEN_AUTO_SELECT',
        applicableFulfillmentModes: ['DELIVERY'],
      },
    ]);

    expect(q('policy-ambiguous')).toBeNull();
    expect((q('policy-mode-DELIVERY') as HTMLInputElement).checked).toBe(true);
    expect((q('policy-mode-PICKUP') as HTMLInputElement).checked).toBe(false);
  });

  it('warns about a hidden group whose override makes it optional, even with exactly one option', () => {
    const { q } = render([
      {
        groupId: 'box',
        sortOrder: 0,
        visibility: 'HIDDEN_AUTO_SELECT',
        requiredOverride: false,
      },
    ]);

    expect(q('policy-ambiguous')?.textContent).toContain('exactly one active option');
  });

  it('saves a hidden group for delivery only, with the attachment’s own version as If-Match', async () => {
    const { q, settle, api, changed } = render([
      { groupId: 'box', sortOrder: 0, visibility: 'HIDDEN_AUTO_SELECT', version: 4 },
    ]);

    (q('policy-mode-PICKUP') as HTMLInputElement).click();
    await settle();
    (q('policy-mode-DINE_IN') as HTMLInputElement).click();
    await settle();
    (q('policy-save') as HTMLButtonElement).click();
    await settle();

    expect(api['setProductAttachmentPolicy']).toHaveBeenCalledWith(SCOPE, 'p1', 'box', 4, {
      visibility: 'HIDDEN_AUTO_SELECT',
      applicableFulfillmentModes: ['DELIVERY'],
      requiredOverride: null,
      minimumSelectionsOverride: null,
      maximumSelectionsOverride: null,
    });
    expect(changed()).toBe(1);
  });

  it('sends every mode as null: a group that names all three is just as universal as one that names none', async () => {
    const { q, settle, api } = render([
      { groupId: 'box', sortOrder: 0, visibility: 'HIDDEN_AUTO_SELECT', version: 1 },
    ]);

    (q('policy-save') as HTMLButtonElement).click();
    await settle();

    expect(api['setProductAttachmentPolicy']).toHaveBeenCalledWith(
      SCOPE,
      'p1',
      'box',
      1,
      expect.objectContaining({ applicableFulfillmentModes: null }),
    );
  });

  it('refuses to save a hidden group that applies to no order type', async () => {
    const { q, settle, api } = render([
      {
        groupId: 'box',
        sortOrder: 0,
        visibility: 'HIDDEN_AUTO_SELECT',
        applicableFulfillmentModes: ['DELIVERY'],
      },
    ]);

    (q('policy-mode-DELIVERY') as HTMLInputElement).click();
    await settle();

    expect(q('policy-no-mode')).not.toBeNull();
    expect((q('policy-save') as HTMLButtonElement).disabled).toBe(true);
    (q('policy-save') as HTMLButtonElement).click();
    await settle();
    expect(api['setProductAttachmentPolicy']).not.toHaveBeenCalled();
  });

  it('writes this product’s own required, minimum and maximum, and sends a blank field as no override', async () => {
    const { q, settle, choose, api } = render([{ groupId: 'sauces', sortOrder: 0, version: 2 }]);

    choose(q('policy-required') as HTMLSelectElement, 'yes');
    (q('policy-minimum') as HTMLInputElement).value = '1';
    (q('policy-maximum') as HTMLInputElement).value = '';
    (q('policy-save') as HTMLButtonElement).click();
    await settle();

    expect(api['setProductAttachmentPolicy']).toHaveBeenCalledWith(SCOPE, 'p1', 'sauces', 2, {
      visibility: 'VISIBLE',
      applicableFulfillmentModes: null,
      requiredOverride: true,
      minimumSelectionsOverride: 1,
      maximumSelectionsOverride: null,
    });
  });

  it('shows an override already set, and reads back a group left on its own value as such', () => {
    const { q } = render([
      {
        groupId: 'sauces',
        sortOrder: 0,
        requiredOverride: false,
        minimumSelectionsOverride: 1,
        maximumSelectionsOverride: null,
        version: 3,
      },
    ]);

    expect((q('policy-required') as HTMLSelectElement).value).toBe('no');
    expect((q('policy-minimum') as HTMLInputElement).value).toBe('1');
    expect((q('policy-maximum') as HTMLInputElement).value).toBe('');
  });

  it('says why a write was refused and does not report a change', async () => {
    const refusal = new ApiError(
      ApiErrorCode.INVALID_REQUEST,
      422,
      { status: 422, detail: 'The effective minimum 3 is above the effective maximum 2' },
      null,
    );
    const { q, settle, changed } = render([{ groupId: 'sauces', sortOrder: 0, version: 1 }], {
      setProductAttachmentPolicy: vi.fn().mockReturnValue(throwError(() => refusal)),
    });

    (q('policy-save') as HTMLButtonElement).click();
    await settle();

    expect(q('policy-error')?.textContent).toContain('above the effective maximum');
    expect(changed()).toBe(0);
  });

  it('shows the groups a variant already offers when it is chosen as a modifier, and lets one more be attached', async () => {
    const { q, host, settle, choose, api, changed } = render([], {}, [attachment('box')]);
    await settle();

    expect(host.querySelectorAll('[data-testid="nested-attached"]')).toHaveLength(1);
    expect(q('nested-attached')?.textContent).toContain('Delivery box');

    const select = q('nested-select') as HTMLSelectElement;
    expect([...select.options].map((option) => option.value)).toEqual(['', 'sauces']);

    choose(select, 'sauces');
    (q('nested-attach') as HTMLButtonElement).click();
    await settle();

    expect(api['attachToVariant']).toHaveBeenCalledWith(SCOPE, 'v1', 'sauces', 1);
    expect(host.querySelectorAll('[data-testid="nested-attached"]')).toHaveLength(2);
    expect(changed()).toBe(1);
  });

  it('says a variant offers nothing of its own when nothing is attached to it', async () => {
    const { q, settle } = render([]);
    await settle();

    expect(q('nested-none')).not.toBeNull();
    expect(q('policy-empty')).not.toBeNull();
  });
});
