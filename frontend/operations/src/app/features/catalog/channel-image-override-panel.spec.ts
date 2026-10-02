import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ChannelImageChoice, ChannelImageOverridePanel } from './channel-image-override-panel';

@Component({
  imports: [ChannelImageOverridePanel],
  template: `
    <q-channel-image-override-panel
      [choices]="choices()"
      [current]="current()"
      [saving]="saving()"
      [error]="error()"
      (saved)="saved.push($event)"
      (cleared)="cleared = cleared + 1"
      (cancelled)="cancelled = cancelled + 1"
    />
  `,
})
class HostComponent {
  readonly choices = signal<readonly ChannelImageChoice[]>([
    { assetId: 'a1', url: 'https://cdn.test/a1.jpg' },
    { assetId: 'a2', url: null },
  ]);
  readonly current = signal<readonly string[]>([]);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  saved: (readonly string[])[] = [];
  cleared = 0;
  cancelled = 0;
}

function render(configure: (host: HostComponent) => void = () => undefined) {
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(HostComponent);
  configure(fixture.componentInstance);
  fixture.detectChanges();
  return fixture;
}

const q = (root: HTMLElement, id: string): HTMLButtonElement | null =>
  root.querySelector(`[data-testid="${id}"]`);

describe('ChannelImageOverridePanel', () => {
  it('cannot save until something is picked, and saves in the order picked', () => {
    const fixture = render();
    const root = fixture.nativeElement as HTMLElement;
    expect(q(root, 'channel-override-save')?.disabled).toBe(true);

    const tiles = root.querySelectorAll<HTMLButtonElement>(
      '[data-testid="channel-override-choice"]',
    );
    tiles[1].click();
    fixture.detectChanges();
    tiles[0].click();
    fixture.detectChanges();
    q(root, 'channel-override-save')?.click();

    expect(fixture.componentInstance.saved).toEqual([['a2', 'a1']]);
  });

  it('starts from the photos the channel shows now, and offers to go back to the usual ones', () => {
    const fixture = render((host) => host.current.set(['a1']));
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('[data-testid="channel-override-order"]')?.textContent?.trim()).toBe(
      '1',
    );
    q(root, 'channel-override-clear')?.click();
    expect(fixture.componentInstance.cleared).toBe(1);
  });

  it('offers no way back to the defaults when there is no override to remove', () => {
    const fixture = render();
    expect(q(fixture.nativeElement as HTMLElement, 'channel-override-clear')).toBeNull();
  });

  it('says so when the product has no photos of its own to pick from', () => {
    const fixture = render((host) => host.choices.set([]));
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="channel-override-empty"]',
      ),
    ).not.toBeNull();
  });

  it('shows a failed save and locks the buttons while one is in flight', () => {
    const fixture = render((host) => {
      host.saving.set(true);
      host.error.set('Could not save');
      host.current.set(['a1']);
    });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('Could not save');
    expect(q(root, 'channel-override-save')?.disabled).toBe(true);
    expect(q(root, 'channel-override-clear')?.disabled).toBe(true);
  });
});
