import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import {
  MappingPane,
  MappingPaneCandidate,
  MappingPaneConflict,
  MappingPaneExternalCandidate,
  MappingPaneLinkIntent,
  MappingPaneRow,
} from './mapping-pane';

type Fixture = ReturnType<typeof TestBed.createComponent<MappingPane>>;

function render(): Fixture {
  const fixture = TestBed.createComponent(MappingPane);
  fixture.detectChanges();
  return fixture;
}

function el(fixture: Fixture, testId: string): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    `[data-testid="${testId}"]`,
  );
}

function all(fixture: Fixture, testId: string): HTMLElement[] {
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
      `[data-testid="${testId}"]`,
    ),
  );
}

const HORECAOS_CANDIDATES: readonly MappingPaneCandidate[] = [
  { id: 'h1', name: 'Cash' },
  { id: 'h2', name: 'Card' },
];

const EXTERNAL_CANDIDATES: readonly MappingPaneExternalCandidate[] = [
  { externalId: 'e1', name: 'Cash' },
  { externalId: 'e2', name: 'Card' },
];

const LINKED_ROWS: readonly MappingPaneRow[] = [
  {
    mappingId: 'm1',
    horecaosEntityId: 'h3',
    horecaosName: 'Bonus',
    externalEntityId: 'e3',
    status: 'ACTIVE',
    version: 0,
  },
];

const CONFLICT: MappingPaneConflict = {
  name: 'Cash',
  horecaosEntityIds: ['h1', 'h2'],
  externalIds: ['e1', 'e2'],
};

describe('MappingPane', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders both unmapped columns as a dual list', () => {
    const fixture = render();
    fixture.componentRef.setInput('horecaosCandidates', HORECAOS_CANDIDATES);
    fixture.componentRef.setInput('externalCandidates', EXTERNAL_CANDIDATES);
    fixture.detectChanges();

    expect(el(fixture, 'q-mapping-pane-left')).toBeTruthy();
    expect(el(fixture, 'q-mapping-pane-right')).toBeTruthy();
    expect(el(fixture, 'q-mapping-pane-left')!.textContent).toContain('2 unmapped');
    expect(el(fixture, 'q-mapping-pane-right')!.textContent).toContain('2 unmapped');
  });

  it('hides the linked table by default (unmapped-only) and reveals it when toggled off', () => {
    const fixture = render();
    fixture.componentRef.setInput('rows', LINKED_ROWS);
    fixture.detectChanges();

    expect(el(fixture, 'q-mapping-pane-linked')).toBeNull();

    const toggle = el(fixture, 'q-mapping-pane-unmapped-only') as HTMLInputElement;
    toggle.click();
    fixture.detectChanges();

    const linked = el(fixture, 'q-mapping-pane-linked');
    expect(linked).toBeTruthy();
    expect(linked!.textContent).toContain('Bonus');
  });

  it('the link button stays disabled until a candidate is chosen on both sides', () => {
    const fixture = render();
    fixture.componentRef.setInput('horecaosCandidates', HORECAOS_CANDIDATES);
    fixture.componentRef.setInput('externalCandidates', EXTERNAL_CANDIDATES);
    fixture.detectChanges();

    expect((el(fixture, 'q-mapping-pane-link') as HTMLButtonElement).disabled).toBe(true);
  });

  it('links the pair a user actually picks, end to end through the rendered comboboxes', () => {
    const fixture = render();
    fixture.componentRef.setInput('horecaosCandidates', HORECAOS_CANDIDATES);
    fixture.componentRef.setInput('externalCandidates', EXTERNAL_CANDIDATES);
    fixture.detectChanges();

    let emitted: MappingPaneLinkIntent | undefined;
    fixture.componentInstance.link.subscribe((intent) => (emitted = intent));

    const leftInput = el(fixture, 'q-mapping-pane-left')!.querySelector<HTMLInputElement>(
      '[data-testid="q-combobox-input"]',
    )!;
    leftInput.value = 'Cash';
    leftInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const leftOption = el(fixture, 'q-mapping-pane-left')!.querySelector<HTMLElement>(
      '[data-testid="q-combobox-option"]',
    )!;
    leftOption.click();
    fixture.detectChanges();

    const rightInput = el(fixture, 'q-mapping-pane-right')!.querySelector<HTMLInputElement>(
      '[data-testid="q-combobox-input"]',
    )!;
    rightInput.value = 'Cash';
    rightInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const rightOption = el(fixture, 'q-mapping-pane-right')!.querySelector<HTMLElement>(
      '[data-testid="q-combobox-option"]',
    )!;
    rightOption.click();
    fixture.detectChanges();

    const linkButton = el(fixture, 'q-mapping-pane-link') as HTMLButtonElement;
    expect(linkButton.disabled).toBe(false);
    linkButton.click();

    expect(emitted).toEqual({ horecaosId: 'h1', externalId: 'e1' });
  });

  it('emits unlink for a linked row', () => {
    const fixture = render();
    fixture.componentRef.setInput('rows', LINKED_ROWS);
    fixture.detectChanges();
    (el(fixture, 'q-mapping-pane-unmapped-only') as HTMLInputElement).click();
    fixture.detectChanges();

    let emitted: MappingPaneRow | undefined;
    fixture.componentInstance.unlink.subscribe((row) => (emitted = row));

    (el(fixture, 'q-mapping-pane-unlink') as HTMLButtonElement).click();

    expect(emitted?.mappingId).toBe('m1');
  });

  it('emits bulkAutoMatch on the toolbar button', () => {
    const fixture = render();
    let firedCount = 0;
    fixture.componentInstance.bulkAutoMatch.subscribe(() => (firedCount += 1));

    (el(fixture, 'q-mapping-pane-bulk-auto-match') as HTMLButtonElement).click();

    expect(firedCount).toBe(1);
  });

  it('renders a two-sided conflict card naming every candidate on both sides, and never picks one on its own', () => {
    const fixture = render();
    fixture.componentRef.setInput('conflicts', [CONFLICT]);
    fixture.detectChanges();

    const card = el(fixture, 'q-mapping-pane-conflict-card')!;
    expect(card.textContent).toContain('Cash');
    const horecaosEntityIds = all(fixture, 'q-mapping-pane-conflict-horecaos-id').map((n) =>
      n.textContent?.trim(),
    );
    const externalIds = all(fixture, 'q-mapping-pane-conflict-external-id').map((n) =>
      n.textContent?.trim(),
    );
    expect(horecaosEntityIds).toEqual(['h1', 'h2']);
    expect(externalIds).toEqual(['e1', 'e2']);
    // Two candidates on each side: nothing to "link this pair" for, since a
    // two-sided conflict is never resolved by the pane on its own.
    expect(el(fixture, 'q-mapping-pane-conflict-link')).toBeNull();
  });

  it('lets an operator resolve a narrowed (1-to-1) conflict from inside the card, and dismiss one entirely', () => {
    const fixture = render();
    const narrowed: MappingPaneConflict = {
      name: 'Cash',
      horecaosEntityIds: ['h1'],
      externalIds: ['e1'],
    };
    fixture.componentRef.setInput('conflicts', [narrowed]);
    fixture.detectChanges();

    let linked: MappingPaneLinkIntent | undefined;
    fixture.componentInstance.link.subscribe((intent) => (linked = intent));
    (el(fixture, 'q-mapping-pane-conflict-link') as HTMLButtonElement).click();
    expect(linked).toEqual({ horecaosId: 'h1', externalId: 'e1' });

    let dismissed: MappingPaneConflict | undefined;
    fixture.componentInstance.dismissConflict.subscribe((conflict) => (dismissed = conflict));
    (el(fixture, 'q-mapping-pane-conflict-dismiss') as HTMLButtonElement).click();
    expect(dismissed).toEqual(narrowed);
  });

  it("lets an operator type the provider's own code when this build cannot source that side at all", () => {
    const fixture = render();
    fixture.componentRef.setInput('horecaosCandidates', HORECAOS_CANDIDATES);
    fixture.componentRef.setInput('externalSourced', false);
    fixture.detectChanges();

    const leftInput = el(fixture, 'q-mapping-pane-left')!.querySelector<HTMLInputElement>(
      '[data-testid="q-combobox-input"]',
    )!;
    leftInput.value = 'Cash';
    leftInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el(fixture, 'q-mapping-pane-left')!
      .querySelector<HTMLElement>('[data-testid="q-combobox-option"]')!
      .click();
    fixture.detectChanges();

    const rightInput = el(fixture, 'q-mapping-pane-right')!.querySelector<HTMLInputElement>(
      '[data-testid="q-combobox-input"]',
    )!;
    rightInput.value = 'POS-777';
    rightInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    const createRow = el(fixture, 'q-mapping-pane-right')!.querySelector<HTMLElement>(
      '[data-testid="q-combobox-create-row"]',
    );
    expect(createRow).toBeTruthy();
    createRow!.click();
    fixture.detectChanges();

    let emitted: MappingPaneLinkIntent | undefined;
    fixture.componentInstance.link.subscribe((intent) => (emitted = intent));
    (el(fixture, 'q-mapping-pane-link') as HTMLButtonElement).click();

    expect(emitted).toEqual({ horecaosId: 'h1', externalId: 'POS-777' });
  });

  it('offers no create-on-miss row on the provider side once this build can source it', () => {
    const fixture = render();
    fixture.componentRef.setInput('externalSourced', true);
    fixture.detectChanges();

    const rightInput = el(fixture, 'q-mapping-pane-right')!.querySelector<HTMLInputElement>(
      '[data-testid="q-combobox-input"]',
    )!;
    rightInput.value = 'anything';
    rightInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(
      el(fixture, 'q-mapping-pane-right')!.querySelector('[data-testid="q-combobox-create-row"]'),
    ).toBeNull();
  });
});
