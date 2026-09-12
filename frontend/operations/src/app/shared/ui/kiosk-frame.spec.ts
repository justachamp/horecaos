import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { KioskFrame } from './kiosk-frame';

@Component({
  imports: [KioskFrame],
  template: `
    <q-kiosk-frame [caption]="caption">
      <p class="q-body-sm">Лагман — 32 000 сум</p>
    </q-kiosk-frame>
  `,
})
class HostComponent {
  caption: string | null = null;
}

describe('KioskFrame', () => {
  it('renders projected content inside the kiosk screen area', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-kiosk-frame"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="q-kiosk-frame-screen"]')?.textContent).toContain(
      'Лагман',
    );
  });

  it('renders the caller’s caption, when given one', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.caption = 'Экран киоска в режиме ожидания';
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('figcaption')?.textContent).toBe(
      'Экран киоска в режиме ожидания',
    );
  });
});
