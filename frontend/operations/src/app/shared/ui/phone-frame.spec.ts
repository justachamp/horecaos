import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { PhoneFrame } from './phone-frame';

/** A storefront-shaped sample — this frame family's only honest content until ADR 0040 lands (see `PhoneFrame`'s own doc comment). */
@Component({
  imports: [PhoneFrame],
  template: `
    <q-phone-frame [caption]="caption">
      <p class="q-body-sm">Лагман — 32 000 сум</p>
    </q-phone-frame>
  `,
})
class HostComponent {
  caption: string | null = null;
}

describe('PhoneFrame', () => {
  it('renders the bezel, notch and screen chrome around projected content', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-phone-frame"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="q-phone-frame-screen"]')?.textContent).toContain(
      'Лагман',
    );
  });

  it('renders no caption when the caller supplies none', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('figcaption')).toBeNull();
  });

  it('renders the caller’s caption under the frame', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.caption = 'Как увидит клиент';
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('figcaption')?.textContent).toBe(
      'Как увидит клиент',
    );
  });
});
