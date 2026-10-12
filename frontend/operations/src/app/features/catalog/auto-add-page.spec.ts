import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { Combobox } from '../../shared/ui/combobox';
import { AutoAddApi, AutoAddRule } from './auto-add-api';
import { AutoAddPage } from './auto-add-page';
import { CatalogApi } from './catalog-api';

const BRAND = { tenantId: 't1', brandId: 'b1' };
const LOCATION = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BOX = { variantId: 'v-box', productName: 'Delivery box', category: 'Packaging' };
const CUTLERY = { variantId: 'v-cutlery', productName: 'Cutlery set', category: 'Packaging' };

function rule(overrides: Partial<AutoAddRule> = {}): AutoAddRule {
  return {
    ruleId: 'rule-1',
    code: 'BOX',
    kind: 'PLAIN',
    mode: 'INJECT',
    status: 'DRAFT',
    addVariantId: BOX.variantId,
    quantity: 1,
    triggerThreshold: null,
    repeatPerMultiple: false,
    minimumQuantity: 1,
    maximumQuantity: 20,
    mandatory: false,
    fulfillmentModes: ['DELIVERY'],
    channelSystemTypes: ['WEB'],
    sortOrder: 0,
    version: 3,
    triggers: [],
    bands: [],
    locationIds: [],
    customerText: {},
    ...overrides,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('AutoAddPage (ADR 0158)', () => {
  let fixture: ComponentFixture<AutoAddPage>;
  let api: Record<'list' | 'create' | 'replace' | 'archive' | 'simulate', ReturnType<typeof vi.fn>>;
  let host: HTMLElement;

  async function render(rules: readonly AutoAddRule[] = [rule()]): Promise<void> {
    api = {
      list: vi.fn().mockResolvedValue(rules),
      create: vi.fn().mockResolvedValue(rule()),
      replace: vi.fn().mockResolvedValue(rule({ status: 'ACTIVE' })),
      archive: vi.fn().mockResolvedValue(rule({ status: 'ARCHIVED' })),
      simulate: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [AutoAddPage],
      providers: [
        { provide: AutoAddApi, useValue: api },
        {
          provide: CatalogApi,
          useValue: {
            variantsAtLocation: vi
              .fn()
              .mockReturnValue(of({ items: [BOX, CUTLERY], nextCursor: null })),
          },
        },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal(BRAND),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: CurrentLocation,
          useValue: { scope: signal(LOCATION), ensureLoaded: () => Promise.resolve() },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(AutoAddPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  /** The combobox the template wired to `testid`, so a pick goes through the page's own handler. */
  function pickIn(testid: string, option: { variantId: string; productName: string }): void {
    const combobox = fixture.debugElement
      .queryAll(By.directive(Combobox))
      .find((candidate) => candidate.nativeElement.getAttribute('data-testid') === testid);
    expect(combobox, testid).toBeDefined();
    (combobox?.componentInstance as Combobox).optionSelected.emit({
      id: option.variantId,
      label: option.productName,
    });
    fixture.detectChanges();
  }

  function setValue(selector: string, value: string): void {
    const field = host.querySelector(selector) as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    field.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('lists a rule with the item’s name and what the rule does, not its id', async () => {
    await render();

    const row = host.querySelector('[data-testid="auto-add-rule-BOX"]');
    expect(row?.textContent).toContain('1 × Delivery box');
    expect(row?.textContent).toContain('Draft');
    expect(row?.textContent).toContain('Add the line');
    expect(row?.textContent).not.toContain('v-box');
  });

  it('says a rule the customer cannot decline cannot be declined', async () => {
    await render([rule({ mandatory: true })]);

    expect(host.querySelector('[data-testid="auto-add-rule-BOX"]')?.textContent).toContain(
      'cannot be declined',
    );
  });

  it('activates a draft with a replace that carries the version it read and the rule’s own fields', async () => {
    await render();

    (host.querySelector('[data-testid="auto-add-toggle-BOX"]') as HTMLButtonElement).click();
    await flush();

    expect(api.replace).toHaveBeenCalledTimes(1);
    const [scope, replaced, body] = api.replace.mock.calls[0];
    expect(scope).toEqual(BRAND);
    expect(replaced.version).toBe(3);
    expect(body.status).toBe('ACTIVE');
    expect(body.addVariantId).toBe('v-box');
    expect(body.fulfillmentModes).toEqual(['DELIVERY']);
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('creates a plain rule from a picked item, a quantity and the modes it applies to', async () => {
    await render([]);

    (host.querySelector('[data-testid="auto-add-create"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const save = host.querySelector('[data-testid="auto-add-save"]') as HTMLButtonElement;
    expect(save.disabled).toBe(true);

    setValue('#rule-code', 'CUTLERY');
    expect(save.disabled).toBe(true);
    pickIn('rule-add-picker', CUTLERY);
    expect(host.querySelector('[data-testid="rule-add-variant"]')?.textContent).toContain(
      'Cutlery set',
    );
    setValue('#rule-quantity', '2');
    expect(save.disabled).toBe(false);

    save.click();
    await flush();

    expect(api.create).toHaveBeenCalledTimes(1);
    const [scope, body] = api.create.mock.calls[0];
    expect(scope).toEqual(BRAND);
    expect(body).toMatchObject({
      code: 'CUTLERY',
      kind: 'PLAIN',
      mode: 'INJECT',
      addVariantId: 'v-cutlery',
      quantity: 2,
      mandatory: false,
    });
    expect(body.fulfillmentModes).toEqual(['DELIVERY', 'PICKUP', 'DINE_IN']);
    expect(body.channelSystemTypes).not.toContain('AGGREGATOR');
  });

  it('will not save a rule with no fulfilment mode or no channel', async () => {
    await render([]);
    (host.querySelector('[data-testid="auto-add-create"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    setValue('#rule-code', 'X');
    pickIn('rule-add-picker', BOX);
    const save = host.querySelector('[data-testid="auto-add-save"]') as HTMLButtonElement;
    expect(save.disabled).toBe(false);

    for (const input of Array.from(
      host.querySelectorAll('[data-testid="auto-add-form"] .checks')[0].querySelectorAll('input'),
    )) {
      (input as HTMLInputElement).click();
    }
    fixture.detectChanges();

    expect(save.disabled).toBe(true);
  });

  it('shows the server’s refusal of a channel in words, and keeps the form open', async () => {
    await render([]);
    api.create.mockRejectedValue(
      new ApiError(
        ApiErrorCode.UNPROCESSABLE_STATE,
        422,
        {
          status: 422,
          code: ApiErrorCode.UNPROCESSABLE_STATE,
          detail: 'x',
          findingCode: 'AUTO_ADD_CHANNEL_NOT_ALLOWED',
        } as never,
        null,
      ),
    );
    (host.querySelector('[data-testid="auto-add-create"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    setValue('#rule-code', 'X');
    pickIn('rule-add-picker', BOX);

    (host.querySelector('[data-testid="auto-add-save"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="auto-add-form-error"]')?.textContent).toContain(
      'aggregator or POS',
    );
    expect(host.querySelector('[data-testid="auto-add-form"]')).not.toBeNull();
  });

  it('runs a basket through the rules and says what each would do, and why one would not', async () => {
    await render();
    api.simulate.mockResolvedValue({
      units: 2,
      portions: 2,
      outcomes: [
        {
          ruleId: 'rule-1',
          ruleCode: 'BOX',
          ruleStatus: 'DRAFT',
          outcome: 'INJECT',
          variantId: 'v-box',
          quantity: 1,
          reason: null,
        },
        {
          ruleId: 'rule-2',
          ruleCode: 'GIFT',
          ruleStatus: 'ACTIVE',
          outcome: 'SKIPPED',
          variantId: null,
          quantity: null,
          reason: 'FULFILLMENT_MODE_NOT_MATCHED',
        },
      ],
    });
    const run = host.querySelector('[data-testid="auto-add-simulate"]') as HTMLButtonElement;
    expect(run.disabled).toBe(true);

    pickIn('auto-add-sim-picker', CUTLERY);
    expect(run.disabled).toBe(false);
    run.click();
    await flush();
    fixture.detectChanges();

    expect(api.simulate).toHaveBeenCalledWith(BRAND, {
      fulfillmentMode: 'DELIVERY',
      channelSystemType: 'WEB',
      locationId: 'l1',
      lines: [{ variantId: 'v-cutlery', quantity: 1 }],
    });
    expect(host.querySelector('[data-testid="auto-add-sim-outcome-BOX"]')?.textContent).toContain(
      'Would add',
    );
    expect(host.querySelector('[data-testid="auto-add-sim-outcome-BOX"]')?.textContent).toContain(
      '1 × Delivery box',
    );
    expect(host.querySelector('[data-testid="auto-add-sim-outcome-GIFT"]')?.textContent).toContain(
      'FULFILLMENT_MODE_NOT_MATCHED',
    );
  });
});
