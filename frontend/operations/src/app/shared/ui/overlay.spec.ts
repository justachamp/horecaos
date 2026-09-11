import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ConfirmDialog } from './confirm-dialog';
import { Drawer } from './drawer';

/**
 * Cross-component: whether Escape respects overlay *stacking*, not any one
 * overlay's own behaviour — `drawer.spec.ts` and `confirm-dialog.spec.ts`
 * each already cover a single overlay closing on Escape. This is the
 * scenario `overlay.ts`'s own doc names as the reason `q-confirm-dialog`
 * exists at all: "the confirmation whose refusal would lose work",
 * opened from inside another overlay.
 */
@Component({
  selector: 'q-nested-overlay-host',
  imports: [Drawer, ConfirmDialog],
  template: `
    <button type="button" id="invoker" (click)="drawerOpen.set(true)">Filters</button>
    @if (drawerOpen()) {
      <q-drawer title="Filters" (dismiss)="onDrawerDismiss()">
        <button type="button" id="ask" (click)="confirmOpen.set(true)">Discard</button>
        @if (confirmOpen()) {
          <q-confirm-dialog
            title="Discard unsaved filters?"
            confirmLabel="Discard"
            cancelLabel="Keep editing"
            tone="destructive"
            (confirm)="onConfirm()"
            (cancel)="onCancel()"
          />
        }
      </q-drawer>
    }
  `,
})
class NestedOverlayHost {
  readonly drawerOpen = signal(false);
  readonly confirmOpen = signal(false);
  drawerDismissals = 0;
  confirmCancellations = 0;
  confirmations = 0;

  onDrawerDismiss(): void {
    this.drawerDismissals += 1;
    this.drawerOpen.set(false);
  }

  onCancel(): void {
    this.confirmCancellations += 1;
    this.confirmOpen.set(false);
  }

  onConfirm(): void {
    this.confirmations += 1;
  }
}

describe('OverlayBehaviour: Escape and overlay stacking', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<NestedOverlayHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(NestedOverlayHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function openDrawer(): void {
    const invoker = host.querySelector<HTMLButtonElement>('#invoker')!;
    invoker.focus();
    invoker.click();
    fixture.detectChanges();
  }

  function openConfirmOnTop(): void {
    const ask = host.querySelector<HTMLButtonElement>('#ask')!;
    ask.focus();
    ask.click();
    fixture.detectChanges();
  }

  function pressEscape(): void {
    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();
  }

  it('closes only the topmost overlay on Escape, leaving the one underneath open', () => {
    openDrawer();
    openConfirmOnTop();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="q-drawer"]')).not.toBeNull();

    pressEscape();

    // The confirmation is what Escape answered...
    expect(fixture.componentInstance.confirmCancellations).toBe(1);
    expect(fixture.componentInstance.confirmations).toBe(0);
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).toBeNull();
    // ...and the drawer it was opened on top of must not also have closed:
    // a single Escape press must not discard whatever the drawer held.
    expect(fixture.componentInstance.drawerDismissals).toBe(0);
    expect(host.querySelector('[data-testid="q-drawer"]')).not.toBeNull();
  });

  it('lets the drawer answer Escape once the confirmation on top of it is gone', () => {
    openDrawer();
    openConfirmOnTop();
    pressEscape();
    expect(fixture.componentInstance.drawerDismissals).toBe(0);

    pressEscape();

    expect(fixture.componentInstance.drawerDismissals).toBe(1);
    expect(host.querySelector('[data-testid="q-drawer"]')).toBeNull();
  });
});
