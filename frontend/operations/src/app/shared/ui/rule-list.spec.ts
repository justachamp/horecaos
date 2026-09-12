import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { RuleEnabledChange, RuleList, RuleListItem, RuleReorder } from './rule-list';

const ITEMS: readonly RuleListItem[] = [
  { id: 'r1', label: '10% off weekday lunch', description: 'Mon-Fri, 11:00-15:00', enabled: true },
  { id: 'r2', label: 'Free delivery over 150 000 UZS', description: null, enabled: true },
  { id: 'r3', label: 'First-order 20% off', description: 'New customers only', enabled: false },
];

@Component({
  selector: 'q-rule-list-host',
  imports: [RuleList],
  template: `
    <q-rule-list
      [items]="items()"
      (reorder)="lastReorder = $event"
      (enabledChange)="onEnabledChange($event)"
    />
  `,
})
class RuleListHost {
  readonly items = signal<readonly RuleListItem[]>(ITEMS);
  lastReorder: RuleReorder | null = null;
  lastEnabledChange: RuleEnabledChange | null = null;

  onEnabledChange(change: RuleEnabledChange): void {
    this.lastEnabledChange = change;
    this.items.update((current) =>
      current.map((item) => (item.id === change.id ? { ...item, enabled: change.enabled } : item)),
    );
  }
}

describe('RuleList', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<RuleListHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RuleListHost);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function rows(): HTMLLIElement[] {
    return [...host.querySelectorAll<HTMLLIElement>('.rule-list__row')];
  }

  it('shows the honest empty state rather than an empty list when there are no rules', () => {
    fixture.componentInstance.items.set([]);
    fixture.detectChanges();

    expect(host.querySelector('.rule-list')).toBeNull();
    expect(host.textContent).toContain('No rules yet.');
  });

  it('renders every rule labelled with its 1-based priority, in list order', () => {
    expect(rows()).toHaveLength(3);
    expect(rows()[0].textContent).toContain('1');
    expect(rows()[0].textContent).toContain('10% off weekday lunch');
    expect(rows()[2].textContent).toContain('3');
    expect(rows()[2].textContent).toContain('First-order 20% off');
  });

  it('toggling a row emits its id and the new enabled state, and never touches another row', () => {
    const secondToggle = rows()[1].querySelector('input[type="checkbox"]') as HTMLInputElement;
    secondToggle.checked = false;
    secondToggle.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(fixture.componentInstance.lastEnabledChange).toEqual({ id: 'r2', enabled: false });
    expect(fixture.componentInstance.items()[0].enabled).toBe(true);
    expect(fixture.componentInstance.items()[2].enabled).toBe(false);
  });

  describe('keyboard reorder — move up/down', () => {
    it('disables move-up on the first row and move-down on the last row', () => {
      const first = rows()[0];
      const last = rows()[2];
      expect(first.querySelector('[aria-label="Move up in priority"]')).toHaveProperty(
        'disabled',
        true,
      );
      expect(last.querySelector('[aria-label="Move down in priority"]')).toHaveProperty(
        'disabled',
        true,
      );
      expect(first.querySelector('[aria-label="Move down in priority"]')).toHaveProperty(
        'disabled',
        false,
      );
    });

    it('moves a row down and emits the full new id order', () => {
      (
        rows()[0].querySelector('[aria-label="Move down in priority"]') as HTMLButtonElement
      ).click();
      fixture.detectChanges();

      expect(fixture.componentInstance.lastReorder).toEqual(['r2', 'r1', 'r3']);
    });

    it('moves a row up and emits the full new id order', () => {
      (rows()[2].querySelector('[aria-label="Move up in priority"]') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(fixture.componentInstance.lastReorder).toEqual(['r1', 'r3', 'r2']);
    });
  });

  describe('pointer reorder — drag and drop', () => {
    // jsdom (the vitest environment here) has no `DragEvent` global, and the
    // component only reads `event.dataTransfer` through optional chaining —
    // so a plain `Event` of the same type exercises the same listeners
    // without a real drag payload, exactly as the codebase's other native
    // drag-free components already assume no `@angular/cdk` DragEvent shim.
    function dragEvent(type: string): Event {
      return new Event(type, { bubbles: true, cancelable: true });
    }

    it('moves the dragged row to the drop target and emits the full new id order', () => {
      rows()[0].dispatchEvent(dragEvent('dragstart'));
      rows()[2].dispatchEvent(dragEvent('dragover'));
      rows()[2].dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastReorder).toEqual(['r2', 'r3', 'r1']);
    });

    it('does nothing when a row is dropped on itself', () => {
      rows()[1].dispatchEvent(dragEvent('dragstart'));
      rows()[1].dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastReorder).toBeNull();
    });

    it('forgets the drag source once the gesture ends without a drop', () => {
      rows()[0].dispatchEvent(dragEvent('dragstart'));
      rows()[0].dispatchEvent(dragEvent('dragend'));
      rows()[2].dispatchEvent(dragEvent('drop'));
      fixture.detectChanges();

      expect(fixture.componentInstance.lastReorder).toBeNull();
    });
  });
});
