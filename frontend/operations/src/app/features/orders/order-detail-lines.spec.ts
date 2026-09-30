import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderLine } from './order-detail';
import { OrderDetailLines } from './order-detail-lines';

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
});
