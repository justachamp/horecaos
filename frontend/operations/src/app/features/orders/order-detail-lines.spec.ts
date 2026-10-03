import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderLine } from './order-detail';
import { OrderDetailLines, orderLineRows } from './order-detail-lines';

function line(overrides: Partial<OrderLine> = {}): OrderLine {
  return {
    lineId: 'l1',
    lineNumber: 1,
    productName: 'Plov',
    variantName: null,
    quantity: 2,
    finalAmountMinor: 90_000,
    modifiers: [],
    commentPresets: [],
    hasNote: false,
    ...overrides,
  };
}

interface Inputs {
  lines: readonly OrderLine[];
  currency: string;
  revealedNotes: ReadonlyMap<string, string | null>;
  revealingNoteFor: string | null;
}

function render(overrides: Partial<Inputs> = {}) {
  const inputs: Inputs = {
    lines: [line()],
    currency: 'UZS',
    revealedNotes: new Map(),
    revealingNoteFor: null,
    ...overrides,
  };
  const fixture = TestBed.createComponent(OrderDetailLines);
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const row = (index = 0) => host.querySelectorAll('[data-testid="order-detail-line"]')[index];
  return { fixture, host, row };
}

describe('OrderDetailLines', () => {
  beforeEach(() => {
    TestBed.inject(I18n).setLocale('en');
  });

  it('draws one row per line with its number, name, quantity and amount', () => {
    const { host, row } = render({
      lines: [line(), line({ lineId: 'l2', lineNumber: 2, productName: 'Samsa', quantity: 3 })],
    });

    expect(host.querySelectorAll('[data-testid="order-detail-line"]')).toHaveLength(2);
    const cells = [...row(1).querySelectorAll('td')].map((cell) => cell.textContent?.trim());
    expect(cells[0]).toBe('2');
    expect(cells[1]).toBe('Samsa');
    expect(cells[2]).toBe('3');
  });

  it('formats the amount in the order’s currency', () => {
    const { row } = render();

    const amount = row().querySelectorAll('td')[3].textContent ?? '';
    expect(amount.replace(/\s/g, '')).toContain('900');
    expect(amount).toMatch(/UZS|so['’ʻ‘]m|сум|сўм/i);
  });

  it('shows the variant and the modifiers under the name', () => {
    const { row } = render({
      lines: [line({ variantName: 'Large', modifiers: ['Extra meat', 'No onion'] })],
    });

    expect(row().querySelector('.pane__line-variant')?.textContent?.trim()).toBe('Large');
    expect(
      [...row().querySelectorAll('.pane__line-modifiers li')].map((li) => li.textContent?.trim()),
    ).toEqual(['Extra meat', 'No onion']);
  });

  it('words the comment presets in the operator’s language', () => {
    const presets = [
      { code: 'NO_SPICE', labelRu: 'Не остро', labelUz: 'Achchiq emas', labelEn: 'Not spicy' },
    ];
    const en = render({ lines: [line({ commentPresets: presets })] });
    expect(en.row().querySelector('.pane__preset-chip')?.textContent?.trim()).toBe('Not spicy');

    TestBed.inject(I18n).setLocale('ru');
    const ru = render({ lines: [line({ commentPresets: presets })] });
    expect(ru.row().querySelector('.pane__preset-chip')?.textContent?.trim()).toBe('Не остро');
  });

  it('hides a note behind a reveal button, and reports which line was asked for', () => {
    const { fixture, host } = render({ lines: [line({ lineId: 'l9', hasNote: true })] });
    const asked: string[] = [];
    fixture.componentInstance.noteRevealRequested.subscribe((id) => asked.push(id));

    host
      .querySelector<HTMLButtonElement>('[data-testid="order-detail-line-note-reveal-l9"]')
      ?.click();

    expect(asked).toEqual(['l9']);
  });

  it('disables the reveal button for the line whose note is being fetched', () => {
    const { host } = render({
      lines: [line({ lineId: 'l9', hasNote: true })],
      revealingNoteFor: 'l9',
    });

    const button = host.querySelector<HTMLButtonElement>(
      '[data-testid="order-detail-line-note-reveal-l9"]',
    );
    expect(button?.disabled).toBe(true);
  });

  it('shows a revealed note in place of the button, and says so when it turned out empty', () => {
    const revealed = render({
      lines: [line({ lineId: 'l9', hasNote: true })],
      revealedNotes: new Map([['l9', 'Ring twice']]),
    });
    expect(revealed.row().querySelector('.pane__line-note')?.textContent?.trim()).toBe(
      'Ring twice',
    );
    expect(revealed.host.querySelector('.pane__reveal-link')).toBeNull();

    const empty = render({
      lines: [line({ lineId: 'l9', hasNote: true })],
      revealedNotes: new Map([['l9', null]]),
    });
    expect(empty.row().querySelector('.pane__line-note')?.textContent?.trim()).toBe('no note');
  });

  it('draws no note control for a line that has no note', () => {
    const { row } = render();

    expect(row().querySelector('.pane__line-note')).toBeNull();
    expect(row().querySelector('.pane__reveal-link')).toBeNull();
  });

  // ------------------------------------------------------------ ADR 0136

  const combo = (selectionId: string, name = 'Lunch box', quantity = 2) => ({
    selectionId,
    containerVariantId: 'cv-1',
    name,
    quantity,
  });

  it('draws a combo as a header with its components beneath, the header adding the components up', () => {
    const { host } = render({
      lines: [
        line({
          lineId: 'a',
          lineNumber: 1,
          productName: 'Plov',
          combo: null,
          finalAmountMinor: 10_000,
        }),
        line({
          lineId: 'b',
          lineNumber: 2,
          productName: 'Burger',
          combo: combo('sel-1'),
          finalAmountMinor: 50_000,
        }),
        line({
          lineId: 'c',
          lineNumber: 3,
          productName: 'Cola',
          combo: combo('sel-1'),
          finalAmountMinor: 6_000,
        }),
      ],
    });

    const rows = [...host.querySelectorAll('tbody tr')].map((row) =>
      row.getAttribute('data-testid'),
    );
    expect(rows).toEqual([
      'order-detail-line',
      'order-detail-combo',
      'order-detail-line',
      'order-detail-line',
    ]);
    const head = host.querySelector('[data-testid="order-detail-combo"]') as HTMLElement;
    expect(head.textContent).toContain('Lunch box');
    expect(head.textContent).toContain('2');
    expect(head.textContent).toMatch(/56\s?000/);
  });

  it('keeps one combo’s components together even when another line was ordered between them', () => {
    const rows = orderLineRows([
      line({ lineId: 'a', combo: combo('sel-1') }),
      line({ lineId: 'b', combo: null }),
      line({ lineId: 'c', combo: combo('sel-1') }),
      line({ lineId: 'd', combo: combo('sel-2', 'Family box') }),
    ]);

    expect(rows.map((row) => (row.kind === 'combo' ? row.name : row.line.lineId))).toEqual([
      'Lunch box',
      'a',
      'c',
      'b',
      'Family box',
      'd',
    ]);
  });

  it('shows a line with no combo exactly as before: no header', () => {
    const { host } = render({ lines: [line(), line({ lineId: 'l2' })] });

    expect(host.querySelector('[data-testid="order-detail-combo"]')).toBeNull();
  });

  it('itemises a charge the server added: its name, what it cost, and that the customer did not choose it', () => {
    const { host } = render({
      lines: [
        line({
          modifiers: ['Extra cheese', 'Delivery box'],
          autoSelectedModifiers: ['Delivery box'],
          autoSelectedCharges: [{ name: 'Delivery box', amountMinor: 2_000 }],
        }),
      ],
    });

    const auto = host.querySelector('[data-testid="order-detail-line-auto"]') as HTMLElement;
    expect(auto.textContent).toContain('Delivery box');
    expect(auto.textContent).toMatch(/2\s?000/);
    expect(auto.textContent).toContain('added automatically');
    const chosen = host.querySelector('.pane__line-modifiers') as HTMLElement;
    expect(chosen.textContent).toContain('Extra cheese');
    expect(chosen.textContent).not.toContain('Delivery box');
  });

  it('lists a hidden option under the chosen ones only when the platform did not say it was applied', () => {
    const { host } = render({ lines: [line({ modifiers: ['Large'] })] });

    expect(host.querySelector('[data-testid="order-detail-line-auto"]')).toBeNull();
    expect(host.querySelector('.pane__line-modifiers')?.textContent).toContain('Large');
  });

  describe('portions and weighed lines (ADR 0137)', () => {
    const NBSP = '\u00a0';

    it('writes a portion as the console writes a quantity, and a whole quantity without decimals', () => {
      const { row } = render({
        lines: [line({ quantity: 0.5 }), line({ lineId: 'l2', lineNumber: 2, quantity: 3 })],
      });

      expect(row(0).querySelectorAll('td')[2].textContent?.trim()).toBe('0.5');
      expect(row(1).querySelectorAll('td')[2].textContent?.trim()).toBe('3');
    });

    it('writes the decimal mark of the console’s language', () => {
      TestBed.inject(I18n).setLocale('ru');
      const { row } = render({ lines: [line({ quantity: 0.5 })] });

      expect(row(0).querySelectorAll('td')[2].textContent?.trim()).toBe('0,5');
    });

    it('says a weighed line is an estimate until it is weighed, with the price per quantum', () => {
      const { row } = render({
        lines: [
          line({
            quantity: 2,
            catchweight: {
              quantumGrams: 100,
              nominalGramsPerUnit: 1_200,
              pricePerQuantumMinor: 15_000,
              provisional: true,
            },
          }),
        ],
      });

      const weight = row(0).querySelector('[data-testid="order-detail-line-weight"]')!;
      expect(weight.textContent).toContain(`2.4${NBSP}kg`);
      expect(weight.textContent).toContain('estimate');
      expect(weight.textContent).toContain(`100${NBSP}g`);
    });

    it('shows the weighed weight once it is weighed, and no longer calls it an estimate', () => {
      const { row } = render({
        lines: [
          line({
            quantity: 1,
            catchweight: {
              quantumGrams: 100,
              nominalGramsPerUnit: 1_200,
              pricePerQuantumMinor: 15_000,
              provisional: false,
              actualWeightGrams: 1_340,
            },
          }),
        ],
      });

      const weight = row(0).querySelector('[data-testid="order-detail-line-weight"]')!;
      expect(weight.textContent).toContain(`1.34${NBSP}kg`);
      expect(weight.textContent).toContain('Weighed');
      expect(weight.textContent).not.toContain('estimate');
    });

    it('draws nothing about weight on a line that is not sold by weight', () => {
      const { row } = render({ lines: [line()] });

      expect(row(0).querySelector('[data-testid="order-detail-line-weight"]')).toBeNull();
    });
  });
});
