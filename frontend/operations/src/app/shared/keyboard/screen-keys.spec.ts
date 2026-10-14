import { Component, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ShortcutRegistry } from './shortcut-registry';
import {
  FIELD_SELECTOR,
  controlIn,
  currentItem,
  focusFirst,
  itemsOf,
  moveFocus,
  press,
  registerScreenKeys,
} from './screen-keys';

@Component({
  selector: 'q-keys-host',
  template: `
    <ul>
      <li class="row" id="one"><button class="go" type="button">go</button></li>
      <li class="row" id="two">
        <button class="go" type="button" disabled>go</button>
      </li>
      <li class="row" id="three"><input class="field" /></li>
    </ul>
  `,
})
class Host {
  readonly answers: boolean[] = [];

  constructor() {
    registerScreenKeys((screen) => ({
      id: 'host',
      title: () => 'Host',
      shortcuts: [
        {
          keys: ['q'],
          caps: ['q'],
          label: () => screen.locale(),
          run: () =>
            void screen
              .confirm({ title: 'Sure?', confirmLabel: 'Yes', cancelLabel: 'No' })
              .then((a) => this.answers.push(a)),
        },
      ],
    }));
    inject(I18n).setLocale('en');
  }
}

function mount() {
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  document.body.appendChild(fixture.nativeElement);
  return fixture;
}

afterEach(() => {
  document.body.querySelectorAll('q-keys-host, q-confirm-dialog').forEach((node) => node.remove());
});

describe('screen keys: the DOM a keys file works through', () => {
  it('registers a scope for the life of the component and no longer', () => {
    const fixture = mount();
    const registry = TestBed.inject(ShortcutRegistry);

    expect(registry.scopes().map((scope) => scope.id)).toEqual(['host']);
    expect(registry.scopes()[0].shortcuts[0].label()).toBe('en');

    fixture.destroy();
    expect(registry.scopes()).toEqual([]);
  });

  it('moves focus along the items, stops at the ends and starts from the right end', () => {
    const host = mount().nativeElement as HTMLElement;
    const ids = (): string | undefined => (document.activeElement as HTMLElement | null)?.id;

    expect(moveFocus(host, '.row', 1)?.id).toBe('one');
    expect(ids()).toBe('one');
    moveFocus(host, '.row', 1);
    moveFocus(host, '.row', 1);
    moveFocus(host, '.row', 1);
    expect(ids()).toBe('three');
    (document.activeElement as HTMLElement).blur();
    expect(moveFocus(host, '.row', -1)?.id).toBe('three');
    moveFocus(host, '.row', -1);
    moveFocus(host, '.row', -1);
    moveFocus(host, '.row', -1);
    expect(ids()).toBe('one');
    expect(moveFocus(host, '.nothing', 1)).toBeNull();
  });

  it('makes a row focusable without putting it in the Tab order', () => {
    const host = mount().nativeElement as HTMLElement;

    moveFocus(host, '.row', 1);

    expect(host.querySelector('#one')!.getAttribute('tabindex')).toBe('-1');
  });

  it('knows the item that holds or contains focus', () => {
    const host = mount().nativeElement as HTMLElement;
    host.querySelector<HTMLElement>('.field')!.focus();

    expect(currentItem(host, '.row')?.id).toBe('three');
    (document.activeElement as HTMLElement).blur();
    expect(currentItem(host, '.row')).toBeNull();
  });

  it('presses an enabled control and leaves a disabled or missing one alone', () => {
    const host = mount().nativeElement as HTMLElement;
    const one = host.querySelector<HTMLElement>('#one')!;
    const two = host.querySelector<HTMLElement>('#two')!;
    const clicked = vi.fn();
    one.querySelector('.go')!.addEventListener('click', clicked);
    two.querySelector('.go')!.addEventListener('click', clicked);

    expect(press(one, '.go')).toBe(true);
    expect(press(two, '.go')).toBe(false);
    expect(press(one, '.absent')).toBe(false);
    expect(clicked).toHaveBeenCalledOnce();
    expect(controlIn(two, '.go')).toBeNull();
    expect(itemsOf(host, '.row')).toHaveLength(3);
  });

  it('finds the first field of a region', () => {
    const host = mount().nativeElement as HTMLElement;

    expect(focusFirst(host, FIELD_SELECTOR)).toBe(true);
    expect((document.activeElement as HTMLElement).className).toBe('go');
    expect(focusFirst(host, '.absent')).toBe(false);
  });

  it('asks a question in the confirm dialog, with Cancel the answer no and the primary button yes', async () => {
    const fixture = mount();
    const host = fixture.componentInstance;
    const registry = TestBed.inject(ShortcutRegistry);
    const event = new KeyboardEvent('keydown', { key: 'q', cancelable: true, bubbles: true });
    Object.defineProperty(event, 'target', { value: document.body });

    registry.dispatch(event);
    await Promise.resolve();
    const dialog = document.body.querySelector('q-confirm-dialog')!;
    expect(dialog.textContent).toContain('Sure?');
    const buttons = [...dialog.querySelectorAll<HTMLButtonElement>('button')];
    buttons.find((button) => button.textContent!.trim() === 'Yes')!.click();
    await Promise.resolve();

    expect(host.answers).toEqual([true]);
    expect(document.body.querySelector('q-confirm-dialog')).toBeNull();
  });
});
