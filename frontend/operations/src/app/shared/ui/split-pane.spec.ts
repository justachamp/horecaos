import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  SPLIT_PANE_MIN_SECONDARY_PX,
  SPLIT_PANE_STORAGE_PREFIX,
  SplitPane,
  clampWidth,
  readStoredWidth,
} from './split-pane';

@Component({
  selector: 'q-split-host',
  imports: [SplitPane],
  template: `
    <q-split-pane [sectionKey]="section()" [docked]="docked()" handleLabel="Resize">
      <div id="list">list</div>
      <div qSplitDetail id="detail">detail</div>
    </q-split-pane>
  `,
})
class SplitHost {
  readonly section = signal('orders');
  readonly docked = signal(true);
}

function storedWidth(section: string): string | null {
  return localStorage.getItem(SPLIT_PANE_STORAGE_PREFIX + section);
}

describe('SplitPane', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
  });

  afterEach(() => localStorage.clear());

  function render(): ReturnType<typeof TestBed.createComponent<SplitHost>> {
    const fixture = TestBed.createComponent(SplitHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    return fixture;
  }

  function splitElement(
    fixture: ReturnType<typeof TestBed.createComponent<SplitHost>>,
  ): HTMLElement {
    return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="q-split-pane"]',
    )!;
  }

  it('uses its default width when nothing is stored', () => {
    const fixture = render();
    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('420px');
  });

  it('restores a width stored under its own section key', () => {
    localStorage.setItem(SPLIT_PANE_STORAGE_PREFIX + 'orders', '520');
    const fixture = render();

    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('520px');
  });

  it('keeps each section on its own width, so widening orders says nothing about the inbox', () => {
    localStorage.setItem(SPLIT_PANE_STORAGE_PREFIX + 'orders', '520');
    const fixture = render();
    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('520px');

    fixture.componentInstance.section.set('inbox');
    fixture.detectChanges();

    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('420px');
  });

  it('ignores a corrupt stored value rather than rendering it', () => {
    // Every one of these is a real thing another tab, an extension, or a
    // half-finished write can leave behind. None of them may reach the style.
    for (const corrupt of ['', '   ', 'wide', 'NaN', '-1', '0', 'Infinity', '{"px":400}']) {
      localStorage.setItem(SPLIT_PANE_STORAGE_PREFIX + 'orders', corrupt);
      expect(readStoredWidth('orders', 1600)).toBeNull();
    }

    localStorage.setItem(SPLIT_PANE_STORAGE_PREFIX + 'orders', 'wide');
    const fixture = render();
    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('420px');
  });

  it('clamps a stored width that is narrower than the floor or wider than the host', () => {
    expect(clampWidth(10, 1600)).toBe(SPLIT_PANE_MIN_SECONDARY_PX);
    expect(clampWidth(5000, 1000)).toBe(700);
    // A host that has not been measured yet must not clamp everything to the
    // floor — jsdom reports zero for every element.
    expect(clampWidth(520, null)).toBe(520);
  });

  it('resizes from the keyboard and persists the result', () => {
    const fixture = render();
    const handle = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="q-split-pane-handle"]',
    )!;

    handle.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();

    expect(splitElement(fixture).style.getPropertyValue('--q-split-secondary')).toBe('436px');
    expect(storedWidth('orders')).toBe('436');
  });

  it('offers the handle to the keyboard as a real separator', () => {
    const fixture = render();
    const handle = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
      '[data-testid="q-split-pane-handle"]',
    )!;

    expect(handle.getAttribute('role')).toBe('separator');
    expect(handle.getAttribute('tabindex')).toBe('0');
    expect(handle.getAttribute('aria-label')).toBe('Resize');
  });

  it('collapses the detail column while nothing is docked, without unmounting it', () => {
    const fixture = render();
    fixture.componentInstance.docked.set(false);
    fixture.detectChanges();

    expect(splitElement(fixture).className).not.toContain('q-split--docked');
    // Still in the DOM: the router outlet a caller projects here has to stay
    // mounted to report its own activation.
    expect((fixture.nativeElement as HTMLElement).querySelector('#detail')).not.toBeNull();
  });

  it('survives storage that throws, which is what a locked-down kiosk profile does', () => {
    const getItem = Storage.prototype.getItem;
    Storage.prototype.getItem = () => {
      throw new DOMException('denied', 'SecurityError');
    };
    try {
      expect(readStoredWidth('orders', 1600)).toBeNull();
    } finally {
      Storage.prototype.getItem = getItem;
    }
  });
});
