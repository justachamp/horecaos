import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { AddVersionRequest, NotificationsApi, VariableCatalogueEntry } from './notifications-api';
import { PrefillWording, TemplateEditor } from './template-editor';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const CATALOGUE: readonly VariableCatalogueEntry[] = [
  {
    notificationClass: 'TRANSACTIONAL_REQUIRED',
    variables: [
      { name: 'orderNumber', description: "The order's own public number." },
      { name: 'amount', description: 'The total, with its currency.' },
    ],
  },
];

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('TemplateEditor', () => {
  let fixture: ComponentFixture<TemplateEditor>;
  let api: { addVersion: ReturnType<typeof vi.fn>; variableCatalogue: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      addVersion: vi
        .fn()
        .mockResolvedValue({ templateId: 't1', versionNumber: 1, awaitsProviderReview: false }),
      variableCatalogue: vi.fn().mockResolvedValue(CATALOGUE),
    };

    await TestBed.configureTestingModule({
      imports: [TemplateEditor],
      providers: [{ provide: NotificationsApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');

    fixture = TestBed.createComponent(TemplateEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('templateId', 't1');
    fixture.componentRef.setInput('templateKey', 'CONFIRMED');
    fixture.componentRef.setInput('notificationClass', 'TRANSACTIONAL_REQUIRED');
    fixture.componentRef.setInput('channel', 'SMS');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  function setBody(text: string): void {
    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="editor-body"]',
    ) as HTMLTextAreaElement;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('offers the variable catalogue for the template’s own class, fixing the empty-schema defect (gap map X.27)', () => {
    // ADR 0184, D21: the key travels, because one variable exists for one key only.
    expect(api.variableCatalogue).toHaveBeenCalledWith(SCOPE, 'CONFIRMED');
    const chips = fixture.nativeElement.querySelectorAll(
      '[data-testid="variable-chips"] .variable-chip',
    );
    expect(chips.length).toBe(2);
    expect((chips[0] as HTMLElement).textContent).toContain('orderNumber');
  });

  it('inserting a variable chip appends {{name}} to the active locale’s body', () => {
    setBody('Order ');
    const chip = fixture.nativeElement.querySelector(
      '[data-testid="variable-chips"] .variable-chip',
    ) as HTMLButtonElement;
    chip.click();
    fixture.detectChanges();

    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="editor-body"]',
    ) as HTMLTextAreaElement;
    expect(textarea.value).toBe('Order {{orderNumber}}');
  });

  it('renders a live preview substituting a sample value for the placeholder', () => {
    setBody('Order {{orderNumber}} is ready');
    const preview = fixture.nativeElement.querySelector(
      '[data-testid="preview-body"]',
    ) as HTMLElement;
    expect(preview.textContent).toBe('Order A-1042 is ready');
  });

  it('disables save until all three locales have text', () => {
    const save = fixture.nativeElement.querySelector(
      '[data-testid="editor-save"]',
    ) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
  });

  it('derives variablesSchema from the placeholders actually used, never an empty object with a placeholder present', async () => {
    // The exact defect this wave fixes: the old page always sent {},
    // and TemplateRenderer.validate correctly refuses an undeclared
    // placeholder — so a variable-bearing template could never be saved.
    const bodies = [
      'Ваш заказ {{orderNumber}} готов',
      'Buyurtmangiz {{orderNumber}} tayyor',
      'Order {{orderNumber}} is ready',
    ];
    const textareas = () => fixture.nativeElement.querySelectorAll('[data-testid="editor-body"]');
    const tabs = fixture.nativeElement.querySelectorAll('.locale-tab');

    for (let i = 0; i < 3; i += 1) {
      (tabs[i] as HTMLButtonElement).click();
      fixture.detectChanges();
      const textarea = textareas()[0] as HTMLTextAreaElement;
      textarea.value = bodies[i];
      textarea.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    const save = fixture.nativeElement.querySelector(
      '[data-testid="editor-save"]',
    ) as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    save.click();
    await flushMicrotasks();

    expect(api.addVersion).toHaveBeenCalledTimes(1);
    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(request.variablesSchema).toEqual({ orderNumber: 'string' });
    expect(Object.keys(request.variablesSchema)).not.toHaveLength(0);
  });

  it('warns when the saved version awaits its SMS gateway’s approval (ADR 0091, gap map 10.9c)', async () => {
    api.addVersion.mockResolvedValue({
      templateId: 't1',
      versionNumber: 3,
      awaitsProviderReview: true,
    });
    const tabs = fixture.nativeElement.querySelectorAll('.locale-tab');
    for (let i = 0; i < 3; i += 1) {
      (tabs[i] as HTMLButtonElement).click();
      fixture.detectChanges();
      setBody('Text');
    }

    const save = fixture.nativeElement.querySelector(
      '[data-testid="editor-save"]',
    ) as HTMLButtonElement;
    save.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="save-provider-review-warning"]',
      ),
    ).toBeTruthy();
  });

  it('starts from the active version’s own wording when a prefill is supplied', async () => {
    const prefill: readonly PrefillWording[] = [
      { locale: 'ru', subject: null, body: 'Существующий текст' },
    ];
    const prefillFixture = TestBed.createComponent(TemplateEditor);
    prefillFixture.componentRef.setInput('scope', SCOPE);
    prefillFixture.componentRef.setInput('templateId', 't1');
    prefillFixture.componentRef.setInput('templateKey', 'CONFIRMED');
    prefillFixture.componentRef.setInput('notificationClass', 'TRANSACTIONAL_REQUIRED');
    prefillFixture.componentRef.setInput('channel', 'SMS');
    prefillFixture.componentRef.setInput('prefill', prefill);
    prefillFixture.detectChanges();
    await flushMicrotasks();
    prefillFixture.detectChanges();

    const textarea = prefillFixture.nativeElement.querySelector(
      '[data-testid="editor-body"]',
    ) as HTMLTextAreaElement;
    expect(textarea.value).toBe('Существующий текст');
  });
});

/**
 * ADR 0020 as ADR 0149 changed what it counts: a version needs a wording in every language the
 * template's brand serves, not in every language the platform has. The server counts the same set
 * when it saves and activates; the editor asks for exactly that, so a Tashkent restaurant is never
 * made to author Georgian and never told its two-language version is incomplete.
 */
describe('TemplateEditor for a brand that serves two of the languages', () => {
  let fixture: ComponentFixture<TemplateEditor>;
  let api: { addVersion: ReturnType<typeof vi.fn>; variableCatalogue: ReturnType<typeof vi.fn> };
  let served: ReturnType<typeof signal<readonly string[]>>;

  async function open(
    brandLanguages: readonly string[],
    prefill: readonly PrefillWording[] | null = null,
  ): Promise<void> {
    served = signal(brandLanguages);
    api = {
      addVersion: vi
        .fn()
        .mockResolvedValue({ templateId: 't1', versionNumber: 1, awaitsProviderReview: false }),
      variableCatalogue: vi.fn().mockResolvedValue(CATALOGUE),
    };
    await TestBed.configureTestingModule({
      imports: [TemplateEditor],
      providers: [
        { provide: NotificationsApi, useValue: api },
        { provide: LocaleSet, useValue: { locales: served, ensureLoaded: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TemplateEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('templateId', 't1');
    fixture.componentRef.setInput('templateKey', 'CONFIRMED');
    fixture.componentRef.setInput('notificationClass', 'TRANSACTIONAL_REQUIRED');
    fixture.componentRef.setInput('channel', 'SMS');
    if (prefill) {
      fixture.componentRef.setInput('prefill', prefill);
    }
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  const tabs = (): string[] =>
    Array.from(
      fixture.nativeElement.querySelectorAll('.locale-tab') as NodeListOf<HTMLElement>,
    ).map((tab) => (tab.textContent ?? '').trim().split(/\s+/)[0]);

  function type(tabIndex: number, text: string): void {
    (fixture.nativeElement.querySelectorAll('.locale-tab')[tabIndex] as HTMLButtonElement).click();
    fixture.detectChanges();
    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="editor-body"]',
    ) as HTMLTextAreaElement;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  const save = (): HTMLButtonElement =>
    fixture.nativeElement.querySelector('[data-testid="editor-save"]') as HTMLButtonElement;

  it('has a tab for each language the brand serves and none for one it does not', async () => {
    await open(['ru', 'uz-Latn']);

    expect(tabs()).toEqual(['ru', 'uz-Latn']);
  });

  it('can be saved with the two wordings the brand needs, and sends exactly those', async () => {
    await open(['ru', 'uz-Latn']);

    type(0, 'Заказ {{orderNumber}} принят');
    expect(save().disabled).toBe(true);
    type(1, 'Buyurtma {{orderNumber}} qabul qilindi');
    expect(save().disabled).toBe(false);
    save().click();
    await flushMicrotasks();

    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(Object.keys(request.wordings)).toEqual(['ru', 'uz-Latn']);
    expect(request.variablesSchema).toEqual({ orderNumber: 'string' });
  });

  it('asks for a third wording the day the brand adds a third language', async () => {
    await open(['ru', 'uz-Latn']);
    type(0, 'Заказ принят');
    type(1, 'Buyurtma qabul qilindi');
    expect(save().disabled).toBe(false);

    served.set(['ru', 'uz-Latn', 'en']);
    fixture.detectChanges();

    expect(tabs()).toEqual(['ru', 'uz-Latn', 'en']);
    expect(save().disabled).toBe(true);
  });

  it('does not ask for a language the registry declares but cannot send in, even if the brand lists it', async () => {
    await open(['ru', 'kk']);

    expect(tabs()).toEqual(['ru']);
  });

  it('keeps a wording the version already carries in a language the brand does not serve, instead of dropping it', async () => {
    const wording = (locale: string, body: string): PrefillWording => ({
      locale,
      subject: null,
      body,
    });
    await open(
      ['ru', 'uz-Latn'],
      [
        wording('ru', 'Заказ принят'),
        wording('uz-Latn', 'Buyurtma qabul qilindi'),
        wording('en', 'Order accepted'),
      ],
    );

    expect(tabs()).toEqual(['ru', 'uz-Latn']);
    save().click();
    await flushMicrotasks();

    const [, , request] = api.addVersion.mock.calls[0] as [
      LocationScope,
      string,
      AddVersionRequest,
    ];
    expect(Object.keys(request.wordings)).toEqual(['ru', 'uz-Latn', 'en']);
  });
});

/**
 * ADR 0184, row 10.9a: the editor's three additions: the version's «show item prices» switch, copying a
 * text into another language to start its translation, and the note beside the two personal variables.
 */
describe('TemplateEditor as ADR 0184 extends it', () => {
  const ORDER_CATALOGUE: readonly VariableCatalogueEntry[] = [
    {
      notificationClass: 'TRANSACTIONAL_REQUIRED',
      variables: [
        { name: 'orderNumber', description: 'Number.' },
        { name: 'productList', description: 'Items.' },
        { name: 'customerName', description: 'The customer.' },
      ],
    },
  ];

  let fixture: ComponentFixture<TemplateEditor>;
  let api: { addVersion: ReturnType<typeof vi.fn>; variableCatalogue: ReturnType<typeof vi.fn> };

  async function open(
    catalogue: readonly VariableCatalogueEntry[],
    prefill?: readonly PrefillWording[],
  ) {
    api = {
      addVersion: vi
        .fn()
        .mockResolvedValue({ templateId: 't1', versionNumber: 1, awaitsProviderReview: false }),
      variableCatalogue: vi.fn().mockResolvedValue(catalogue),
    };
    await TestBed.configureTestingModule({
      imports: [TemplateEditor],
      providers: [{ provide: NotificationsApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TemplateEditor);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('templateId', 't1');
    fixture.componentRef.setInput('templateKey', 'ORDER_CONFIRMED');
    fixture.componentRef.setInput('notificationClass', 'TRANSACTIONAL_REQUIRED');
    fixture.componentRef.setInput('channel', 'SMS');
    if (prefill) {
      fixture.componentRef.setInput('prefill', prefill);
    }
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  const el = (selector: string): HTMLElement | null =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;

  function typeInto(tabIndex: number, text: string): void {
    (fixture.nativeElement.querySelectorAll('.locale-tab')[tabIndex] as HTMLButtonElement).click();
    fixture.detectChanges();
    const textarea = el('[data-testid="editor-body"]') as HTMLTextAreaElement;
    textarea.value = text;
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function fillAll(text: string): void {
    for (let tab = 0; tab < 3; tab += 1) {
      typeInto(tab, text);
    }
  }

  function setSwitch(on: boolean): void {
    const box = el('[data-testid="show-item-prices"] input') as HTMLInputElement;
    box.checked = on;
    box.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('offers the price switch only to a class that offers the product list', async () => {
    await open(ORDER_CATALOGUE);
    expect(el('[data-testid="show-item-prices"]')).toBeTruthy();

    TestBed.resetTestingModule();
    await open([
      {
        notificationClass: 'TRANSACTIONAL_REQUIRED',
        variables: [{ name: 'orderNumber', description: 'n' }],
      },
    ]);
    expect(el('[data-testid="show-item-prices"]')).toBeNull();
  });

  it('previews the product list with and without prices as the switch moves', async () => {
    await open(ORDER_CATALOGUE);
    typeInto(0, 'You ordered: {{productList}}');

    expect(el('[data-testid="preview-body"]')?.textContent).toBe('You ordered: 2x Lavash, 1x Cola');
    setSwitch(true);
    expect(el('[data-testid="preview-body"]')?.textContent).toBe(
      'You ordered: 2x Lavash (50000 UZS), 1x Cola (8000 UZS)',
    );
  });

  it('sends the switch with the version, and does not send it when it is off', async () => {
    await open(ORDER_CATALOGUE);
    fillAll('{{productList}}');
    setSwitch(true);
    (el('[data-testid="editor-save"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const [, , priced] = api.addVersion.mock.calls[0] as [LocationScope, string, AddVersionRequest];
    expect(priced.showItemPrices).toBe(true);
    expect(priced.variablesSchema).toEqual({ productList: 'string' });
  });

  it('will not save a switch that changes nothing, and says why', async () => {
    await open(ORDER_CATALOGUE);
    fillAll('Thanks for your order');
    setSwitch(true);

    expect(el('[data-testid="show-item-prices-unused"]')).toBeTruthy();
    expect((el('[data-testid="editor-save"]') as HTMLButtonElement).disabled).toBe(true);

    setSwitch(false);
    expect((el('[data-testid="editor-save"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('starts with the switch on when the version it was opened from has it on', async () => {
    await open(ORDER_CATALOGUE, [
      { locale: 'ru', subject: null, body: '{{productList}}', showItemPrices: true },
    ]);

    expect((el('[data-testid="show-item-prices"] input') as HTMLInputElement).checked).toBe(true);
  });

  it('copies the active language’s text into another to start its translation, and says when it replaces', async () => {
    await open(ORDER_CATALOGUE);
    typeInto(0, 'Заказ {{orderNumber}} принят');

    expect(el('[data-testid="copy-to-uz-Latn"]')?.textContent).toContain('Copy into uz-Latn');
    (el('[data-testid="copy-to-uz-Latn"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // It switched to the language it copied into, and the text is there to be translated.
    expect((el('[data-testid="editor-body"]') as HTMLTextAreaElement).value).toBe(
      'Заказ {{orderNumber}} принят',
    );

    typeInto(2, 'Order accepted');
    (fixture.nativeElement.querySelectorAll('.locale-tab')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el('[data-testid="copy-to-en"]')?.textContent).toContain('Replace en with this text');
  });

  it('will not copy an empty text', async () => {
    await open(ORDER_CATALOGUE);

    expect((el('[data-testid="copy-to-uz-Latn"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('says beside the two personal variables that they are looked up on sending and never stored', async () => {
    await open(ORDER_CATALOGUE);
    expect(el('[data-testid="personal-variables-note"]')?.textContent).toContain('never stored');

    TestBed.resetTestingModule();
    await open([
      {
        notificationClass: 'TRANSACTIONAL_REQUIRED',
        variables: [{ name: 'orderNumber', description: 'n' }],
      },
    ]);
    expect(el('[data-testid="personal-variables-note"]')).toBeNull();
  });
});
