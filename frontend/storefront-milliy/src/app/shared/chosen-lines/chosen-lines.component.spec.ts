import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../services/translate.service';
import { ChosenLinesComponent, type ChosenLine } from './chosen-lines.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

function chosen(overrides: Partial<ChosenLine> = {}): ChosenLine {
  return {
    lineKey: 'v1+opt-large',
    name: 'Osh',
    portion: null,
    options: ['Large', 'No onion'],
    quantity: 2,
    available: true,
    ...overrides,
  };
}

@Component({
  standalone: true,
  imports: [ChosenLinesComponent],
  template: `<app-chosen-lines
    [lines]="lines()"
    [busy]="busy()"
    (quantityChange)="changes.push($event)"
  />`,
})
class Host {
  readonly lines = signal<readonly ChosenLine[]>([chosen()]);
  readonly busy = signal(false);
  readonly changes: { lineKey: string; quantity: number }[] = [];
}

function render(lines: readonly ChosenLine[] = [chosen()]) {
  TestBed.configureTestingModule({
    imports: [Host],
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.lines.set(lines);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    state: fixture.componentInstance,
    q: (testId: string) => host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`),
    all: (testId: string) =>
      Array.from(host.querySelectorAll<HTMLButtonElement>(`[data-testid="${testId}"]`)),
  };
}

describe('ChosenLinesComponent', () => {
  it('shows each dish with what the guest chose and how many', () => {
    const view = render([
      chosen(),
      chosen({ lineKey: 'v2+x', name: 'Lagman', options: ['Spicy'], quantity: 1 }),
    ]);

    expect(
      view
        .all('dine-in-custom-line')
        .map((entry) => entry.querySelector('.line__name')?.textContent),
    ).toEqual(['Osh', 'Lagman']);
    expect(
      view.all('dine-in-custom-line-options').map((entry) => entry.textContent?.trim()),
    ).toEqual(['Large, No onion', 'Spicy']);
    expect(view.all('dine-in-custom-quantity').map((entry) => entry.textContent?.trim())).toEqual([
      '2',
      '1',
    ]);
  });

  it('names the portion only when it was given one, and shows no options line for a dish that has none', () => {
    const view = render([chosen({ portion: 'L', options: [] })]);

    expect(view.q('dine-in-custom-line-portion')?.textContent).toContain('L');
    expect(view.q('dine-in-custom-line-options')).toBeNull();
  });

  it('shows no portion line for a dish with a single portion', () => {
    expect(render([chosen()]).q('dine-in-custom-line-portion')).toBeNull();
  });

  it('asks for one more and one fewer of that exact line', () => {
    const view = render();

    view.q('dine-in-custom-increase')!.click();
    view.q('dine-in-custom-decrease')!.click();

    expect(view.state.changes).toEqual([
      { lineKey: 'v1+opt-large', quantity: 3 },
      { lineKey: 'v1+opt-large', quantity: 1 },
    ]);
  });

  it('asks for zero when the last one is lowered, which removes the line', () => {
    const view = render([chosen({ quantity: 1 })]);

    view.q('dine-in-custom-decrease')!.click();

    expect(view.state.changes).toEqual([{ lineKey: 'v1+opt-large', quantity: 0 }]);
  });

  it('waits while a write to the basket is in flight', () => {
    const view = render();
    view.state.busy.set(true);
    view.fixture.detectChanges();

    expect(view.q('dine-in-custom-increase')!.disabled).toBe(true);
    expect(view.q('dine-in-custom-decrease')!.disabled).toBe(true);
    view.q('dine-in-custom-increase')!.click();
    expect(view.state.changes).toEqual([]);
  });

  it('says a dish can no longer be bought and leaves only the way out', () => {
    const view = render([chosen({ available: false })]);

    expect(view.q('dine-in-custom-line-unavailable')).not.toBeNull();
    expect(view.q('dine-in-custom-increase')!.disabled).toBe(true);
    view.q('dine-in-custom-decrease')!.click();
    // The whole line: the platform checks stock on every write of a line, so a PUT
    // of one fewer for a dish that is gone would be refused and the guest stuck.
    expect(view.state.changes).toEqual([{ lineKey: 'v1+opt-large', quantity: 0 }]);
  });
});
