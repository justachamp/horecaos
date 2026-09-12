import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { AggregatorCardFrame } from './aggregator-card-frame';

@Component({
  imports: [AggregatorCardFrame],
  template: `
    <q-aggregator-card-frame [caption]="caption">
      <p class="q-body-sm">Лагман — 32 000 сум</p>
    </q-aggregator-card-frame>
  `,
})
class HostComponent {
  caption: string | null = null;
}

describe('AggregatorCardFrame', () => {
  it('renders the projected content inside a card boundary, not a phone', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="q-aggregator-card-frame"]')).not.toBeNull();
    expect(
      host.querySelector('[data-testid="q-aggregator-card-frame-card"]')?.textContent,
    ).toContain('Лагман');
  });

  it('renders the caller’s caption naming the aggregator, when given one', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.caption = 'Как увидит клиент на Glovo';
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('figcaption')?.textContent).toBe(
      'Как увидит клиент на Glovo',
    );
  });
});
