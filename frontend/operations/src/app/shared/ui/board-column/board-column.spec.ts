import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BoardColumn, BoardColumnDropEvent } from './board-column';

@Component({
  selector: 'q-test-host',
  imports: [BoardColumn],
  template: `
    <q-board-column
      [columnId]="columnId()"
      titleText="K-014"
      [current]="current()"
      [capacity]="capacity()"
      (dropped)="lastDrop.set($event)"
    >
      <div data-testid="card">a card</div>
    </q-board-column>
  `,
})
class TestHost {
  readonly columnId = signal('courier-1');
  readonly current = signal<number | undefined>(1);
  readonly capacity = signal<number | null | undefined>(2);
  readonly lastDrop = signal<BoardColumnDropEvent<unknown> | null>(null);
}

describe('BoardColumn', () => {
  let fixture: ComponentFixture<TestHost>;

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
  }

  it('shows the current/capacity load and projects its cards', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="board-column-load"]')?.textContent).toBe('1 / 2');
    expect(host.querySelector('[data-testid="card"]')?.textContent).toBe('a card');
    expect(host.querySelector('[data-column-id]')?.getAttribute('data-column-id')).toBe(
      'courier-1',
    );
  });

  it('marks the column full once current reaches capacity', async () => {
    await render();
    fixture.componentInstance.current.set(2);
    fixture.detectChanges();

    expect(
      host(fixture)
        .querySelector('[data-testid="board-column-load"]')
        ?.classList.contains('q-board-column__load--full'),
    ).toBe(true);
  });

  it('marks the column over once current exceeds capacity', async () => {
    await render();
    fixture.componentInstance.current.set(3);
    fixture.detectChanges();

    expect(
      host(fixture)
        .querySelector('[data-testid="board-column-load"]')
        ?.classList.contains('q-board-column__load--over'),
    ).toBe(true);
  });

  it('hides the load badge for a column with no current input', async () => {
    await render();
    fixture.componentInstance.current.set(undefined);
    fixture.detectChanges();

    expect(host(fixture).querySelector('[data-testid="board-column-load"]')).toBeNull();
  });

  it('shows a bare count for a pool column with no ceiling', async () => {
    await render();
    fixture.componentInstance.capacity.set(null);
    fixture.componentInstance.current.set(4);
    fixture.detectChanges();

    expect(host(fixture).querySelector('[data-testid="board-column-load"]')?.textContent).toBe('4');
  });
});

function host(fixture: ComponentFixture<TestHost>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}
