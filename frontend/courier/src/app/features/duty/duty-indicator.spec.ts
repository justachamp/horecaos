import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { renderPage, textOf } from '../../../testing/work-harness';
import { DutyStore, type DutyIndicator } from '../../core/telemetry/duty.store';
import { DutyIndicatorBar } from './duty-indicator';

describe('DutyIndicatorBar', () => {
  const indicator = signal<DutyIndicator>('off');

  async function render() {
    indicator.set('off');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: DutyStore, useValue: { indicator } }],
    });
    return renderPage(DutyIndicatorBar, []);
  }

  it('is not there when the courier is not on duty', async () => {
    const bar = await render();

    expect(bar.nativeElement.querySelector('a')).toBeNull();
  });

  const SENTENCES: Readonly<Record<Exclude<DutyIndicator, 'off'>, string>> = {
    tracking: 'On duty · sharing location',
    searching: 'On duty · no position yet, keep the screen on',
    denied: 'On duty · location is blocked',
    break: 'On break · location not shared',
    unreachable: 'On duty · cannot reach the server',
  };

  for (const [state, sentence] of Object.entries(SENTENCES) as [
    Exclude<DutyIndicator, 'off'>,
    string,
  ][]) {
    it(`says "${sentence}" when ${state}, announced politely, and leads to the shift screen`, async () => {
      const bar = await render();

      indicator.set(state);
      bar.detectChanges();

      const link = bar.nativeElement.querySelector('a[href="/shift"]');
      expect(link?.textContent).toContain(sentence);
      expect(bar.nativeElement.querySelector('[role="status"]')).not.toBeNull();
      expect(link?.getAttribute('aria-label')).toBe(null);
    });
  }

  it('marks a degraded state apart from a healthy one, so it is not missed at a glance', async () => {
    const bar = await render();

    indicator.set('tracking');
    bar.detectChanges();
    expect(bar.nativeElement.querySelector('a')?.className).toContain('ok');

    indicator.set('denied');
    bar.detectChanges();
    expect(bar.nativeElement.querySelector('a')?.className).toContain('warn');
    expect(textOf(bar)).toContain('blocked');
  });
});
