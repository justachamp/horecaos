import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { TelegramMiniAppFrame } from './telegram-mini-app-frame';

@Component({
  imports: [TelegramMiniAppFrame],
  template: `
    <q-telegram-mini-app-frame [title]="title" [caption]="caption">
      <p class="q-body-sm">Лагман — 32 000 сум</p>
    </q-telegram-mini-app-frame>
  `,
})
class HostComponent {
  title = 'Rayhon';
  caption: string | null = null;
}

describe('TelegramMiniAppFrame', () => {
  it('renders the Mini App’s own header above the projected content', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(
      host.querySelector('[data-testid="q-telegram-mini-app-frame-header"]')?.textContent,
    ).toContain('Rayhon');
    expect(
      host.querySelector('[data-testid="q-telegram-mini-app-frame-body"]')?.textContent,
    ).toContain('Лагман');
  });

  it('renders the tenant’s own bot name, not a translated word', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.title = 'Osh Markazi';
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-telegram-mini-app-frame-header"]',
      )?.textContent,
    ).toContain('Osh Markazi');
  });
});
