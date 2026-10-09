import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { FocusTrapDirective } from './focus-trap.directive';

@Component({
  imports: [FocusTrapDirective],
  template: `
    <button id="opener" type="button">open</button>
    @if (open()) {
      <div id="dialog" appFocusTrap role="dialog" tabindex="-1">
        <button id="first" type="button">first</button>
        <a id="middle" href="/x">middle</a>
        <button id="last" type="button">last</button>
      </div>
    }
  `,
})
class HostComponent {
  readonly open = signal(false);
}

async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  fixture.detectChanges();
  await fixture.whenStable();
}

function setUp() {
  const fixture = TestBed.createComponent(HostComponent);
  document.body.appendChild(fixture.nativeElement);
  const root = fixture.nativeElement as HTMLElement;
  const el = (id: string) => root.querySelector<HTMLElement>(`#${id}`)!;
  return { fixture, el };
}

function tab(target: HTMLElement, shiftKey = false): KeyboardEvent {
  const event = new KeyboardEvent('keydown', {
    key: 'Tab',
    shiftKey,
    bubbles: true,
    cancelable: true,
  });
  target.dispatchEvent(event);
  return event;
}

describe('FocusTrapDirective', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('moves focus into the dialog when it opens', async () => {
    const { fixture, el } = setUp();
    await settle(fixture);
    el('opener').focus();

    fixture.componentInstance.open.set(true);
    await settle(fixture);

    expect(document.activeElement).toBe(el('first'));
  });

  it('wraps Tab from the last control to the first', async () => {
    const { fixture, el } = setUp();
    fixture.componentInstance.open.set(true);
    await settle(fixture);
    el('last').focus();

    const event = tab(el('last'));

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(el('first'));
  });

  it('wraps Shift+Tab from the first control to the last', async () => {
    const { fixture, el } = setUp();
    fixture.componentInstance.open.set(true);
    await settle(fixture);
    el('first').focus();

    const event = tab(el('first'), true);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(el('last'));
  });

  it('leaves Tab alone between the controls', async () => {
    const { fixture, el } = setUp();
    fixture.componentInstance.open.set(true);
    await settle(fixture);
    el('middle').focus();

    const event = tab(el('middle'));

    expect(event.defaultPrevented).toBe(false);
  });

  it('gives focus back to what opened the dialog when it closes', async () => {
    const { fixture, el } = setUp();
    await settle(fixture);
    el('opener').focus();
    fixture.componentInstance.open.set(true);
    await settle(fixture);

    fixture.componentInstance.open.set(false);
    await settle(fixture);

    expect(document.activeElement).toBe(el('opener'));
  });
});
