import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { Drawer } from './drawer';

@Component({
  selector: 'q-drawer-host',
  imports: [Drawer],
  template: `
    <button type="button" id="invoker" (click)="open.set(true)">Filters</button>
    @if (open()) {
      <q-drawer title="Filters" [side]="side()" (dismiss)="onDismiss()">
        <input id="first" type="text" />
        <button type="button" id="last">Apply</button>
      </q-drawer>
    }
  `,
})
class DrawerHost {
  readonly open = signal(false);
  readonly side = signal<'start' | 'end'>('end');
  dismissals = 0;

  onDismiss(): void {
    this.dismissals += 1;
    this.open.set(false);
  }
}

describe('Drawer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<DrawerHost>>;
  let host: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(DrawerHost);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    host = fixture.nativeElement;
  });

  function openFromInvoker(): HTMLButtonElement {
    const invoker = host.querySelector<HTMLButtonElement>('#invoker')!;
    invoker.focus();
    invoker.click();
    fixture.detectChanges();
    return invoker;
  }

  it('takes focus when it opens and gives it back when it closes', () => {
    const invoker = openFromInvoker();
    expect(document.activeElement?.id).toBe('first');

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    fixture.detectChanges();

    expect(fixture.componentInstance.dismissals).toBe(1);
    expect(document.activeElement).toBe(invoker);
  });

  it('traps Tab inside the panel', () => {
    openFromInvoker();
    host.querySelector<HTMLButtonElement>('#last')!.focus();

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    );

    expect(document.activeElement?.id).toBe('first');
  });

  it('anchors to the trailing edge by default and to the leading edge on request', () => {
    openFromInvoker();
    const backdrop = () => host.querySelector('[data-testid="q-drawer-backdrop"]')!;
    expect(backdrop().className).not.toContain('q-drawer__backdrop--start');

    fixture.componentInstance.side.set('start');
    fixture.detectChanges();

    expect(backdrop().className).toContain('q-drawer__backdrop--start');
  });

  it('declares itself modal and names itself with its own heading', () => {
    openFromInvoker();
    const panel = host.querySelector('[data-testid="q-drawer"]')!;

    expect(panel.getAttribute('aria-modal')).toBe('true');
    const labelledBy = panel.getAttribute('aria-labelledby')!;
    expect(host.querySelector(`#${labelledBy}`)?.textContent?.trim()).toBe('Filters');
  });
});
