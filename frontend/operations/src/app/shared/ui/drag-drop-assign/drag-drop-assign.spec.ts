import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { BoardColumn, BoardColumnDropEvent } from '../board-column/board-column';
import { DragDropAssign, QBoardCardDef } from './drag-drop-assign';
import {
  DragDropAssignColumn,
  DragDropAssignOutcome,
  DragDropAssignMove,
  DragDropAssignRejection,
} from './drag-drop-assign-types';

interface Plan {
  readonly planId: string;
  readonly courierId: string | null;
}

const UNASSIGNED = '__unassigned__';

const PLAN: Plan = { planId: 'plan-1', courierId: null };
const CARRIED_PLAN: Plan = { planId: 'plan-2', courierId: 'courier-1' };

const COLUMNS: readonly DragDropAssignColumn[] = [
  { columnId: UNASSIGNED, titleText: 'Unassigned' },
  { columnId: 'courier-1', titleText: 'K-014', current: 0, capacity: 2 },
  { columnId: 'courier-2', titleText: 'K-015', current: 0, capacity: 2 },
];

@Component({
  selector: 'q-test-host',
  imports: [DragDropAssign, QBoardCardDef],
  template: `
    <q-drag-drop-assign
      [columns]="columns"
      [cards]="cards()"
      [cardId]="cardId"
      [columnIdOf]="columnIdOf"
      [unassignedColumnId]="unassignedColumnId"
      [assignFn]="assignFn"
      [unassignFn]="unassignFn"
      (moved)="lastMoved.set($event)"
      (rejected)="lastRejected.set($event)"
    >
      <ng-template qBoardCard let-plan>
        <span data-testid="plan-ref">{{ plan.planId }}</span>
      </ng-template>
    </q-drag-drop-assign>
  `,
})
class TestHost {
  readonly columns = COLUMNS;
  readonly cards = signal<readonly Plan[]>([PLAN]);
  readonly cardId = (plan: Plan): string => plan.planId;
  readonly columnIdOf = (plan: Plan): string => plan.courierId ?? UNASSIGNED;
  readonly unassignedColumnId = UNASSIGNED;
  readonly assignFn = vi.fn<(plan: Plan, columnId: string) => Promise<DragDropAssignOutcome>>();
  readonly unassignFn = vi.fn<(plan: Plan) => Promise<DragDropAssignOutcome>>();
  readonly lastMoved = signal<DragDropAssignMove<Plan> | null>(null);
  readonly lastRejected = signal<DragDropAssignRejection<Plan> | null>(null);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/**
 * Emits a `q-board-column`'s own `(dropped)` output directly — the same
 * seam `q-board-column.spec.ts` exercises through the DOM. jsdom has no
 * layout, so CDK's pointer-driven drag gesture is never simulated at this
 * tier; what is under test here is what `q-drag-drop-assign` does with the
 * event, not CDK's own gesture tracking.
 */
function emitDrop(
  fixture: ComponentFixture<TestHost>,
  toColumnId: string,
  event: BoardColumnDropEvent<Plan>,
): void {
  const columns = fixture.debugElement.queryAll(By.directive(BoardColumn));
  const target = columns.find(
    (el) => (el.componentInstance as BoardColumn<Plan>).columnId() === toColumnId,
  );
  if (!target) {
    throw new Error(`no q-board-column with columnId ${toColumnId}`);
  }
  (target.componentInstance as BoardColumn<Plan>).dropped.emit(event);
}

function cardIdsIn(fixture: ComponentFixture<TestHost>, columnId: string): readonly string[] {
  const body = (fixture.nativeElement as HTMLElement).querySelector(
    `[data-column-id="${columnId}"] [data-testid="board-column-body"]`,
  );
  return Array.from(body?.querySelectorAll('[data-testid="plan-ref"]') ?? []).map(
    (el) => el.textContent ?? '',
  );
}

describe('DragDropAssign', () => {
  let fixture: ComponentFixture<TestHost>;

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();
  }

  it('renders every card under its current column', async () => {
    await render();
    expect(cardIdsIn(fixture, UNASSIGNED)).toContain('plan-1');
    expect(cardIdsIn(fixture, 'courier-1')).toEqual([]);
  });

  it('optimistically moves a card and confirms it once assignFn applies', async () => {
    await render();
    let resolveAssign!: (outcome: DragDropAssignOutcome) => void;
    fixture.componentInstance.assignFn.mockReturnValue(
      new Promise((resolve) => {
        resolveAssign = resolve;
      }),
    );

    emitDrop(fixture, 'courier-1', {
      card: PLAN,
      fromColumnId: UNASSIGNED,
      toColumnId: 'courier-1',
    });
    fixture.detectChanges();

    // Optimistic: the card is under courier-1 before the promise settles.
    expect(cardIdsIn(fixture, 'courier-1')).toContain('plan-1');
    expect(fixture.componentInstance.assignFn).toHaveBeenCalledWith(PLAN, 'courier-1');

    // In real usage `assignFn` awaits the host's own refresh before it
    // resolves (see `DispatchBoardPage.assignFn`), so `cards()` already
    // carries the plan's new courierId by the time the promise settles —
    // simulated here rather than left to the component's own overlay.
    fixture.componentInstance.cards.set([{ ...PLAN, courierId: 'courier-1' }]);
    resolveAssign({ applied: true });
    await flushMicrotasks();
    fixture.detectChanges();

    expect(cardIdsIn(fixture, 'courier-1')).toContain('plan-1');
    expect(fixture.componentInstance.lastMoved()?.toColumnId).toBe('courier-1');
    expect(fixture.componentInstance.lastRejected()).toBeNull();
  });

  it('surfaces a stale-version refusal and reverts the card instead of silently snapping it back', async () => {
    await render();
    fixture.componentInstance.assignFn.mockResolvedValue({
      applied: false,
      reason: 'STALE_VERSION',
    });

    emitDrop(fixture, 'courier-1', {
      card: PLAN,
      fromColumnId: UNASSIGNED,
      toColumnId: 'courier-1',
    });
    fixture.detectChanges();
    // Optimistic move happened first, exactly as the success path does.
    expect(cardIdsIn(fixture, 'courier-1')).toContain('plan-1');

    await flushMicrotasks();
    fixture.detectChanges();

    // Reverted: back under Unassigned, not left in courier-1.
    expect(cardIdsIn(fixture, 'courier-1')).not.toContain('plan-1');
    expect(cardIdsIn(fixture, UNASSIGNED)).toContain('plan-1');

    // Surfaced, not silent: the rejection event carries the server's own
    // reason, and the component's own alert renders it.
    expect(fixture.componentInstance.lastRejected()?.reason).toBe('STALE_VERSION');
    const alert = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="drag-drop-assign-rejection"]',
    );
    expect(alert).not.toBeNull();
    expect(alert?.textContent).toContain('STALE_VERSION');
  });

  it('reverts and surfaces a rejection when assignFn itself throws (network error), rather than leaving the card stuck', async () => {
    await render();
    fixture.componentInstance.assignFn.mockRejectedValue(new Error('network error'));

    emitDrop(fixture, 'courier-1', {
      card: PLAN,
      fromColumnId: UNASSIGNED,
      toColumnId: 'courier-1',
    });
    fixture.detectChanges();
    // Optimistic move happened first, exactly as the success/refusal paths do.
    expect(cardIdsIn(fixture, 'courier-1')).toContain('plan-1');

    await flushMicrotasks();
    fixture.detectChanges();

    // Reverted: back under Unassigned, not left in courier-1 or in limbo.
    expect(cardIdsIn(fixture, 'courier-1')).not.toContain('plan-1');
    expect(cardIdsIn(fixture, UNASSIGNED)).toContain('plan-1');

    // The catch branch synthesizes {applied: false, reason: null} -- distinct
    // from the STALE_VERSION case, and rendered through rejectedUnknown
    // rather than the reason-carrying message.
    expect(fixture.componentInstance.lastRejected()?.reason).toBeNull();
    const alert = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="drag-drop-assign-rejection"]',
    );
    expect(alert).not.toBeNull();
    expect(alert?.textContent).not.toContain('null');

    // pendingCardIds was cleared in the finally block, so the card is
    // draggable again -- nothing left it stuck mid-drag.
    const card = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="board-card"]'),
    ).find((el) => el.textContent?.includes('plan-1'));
    expect(card).not.toBeUndefined();
    expect(card?.classList.contains('q-board-card--disabled')).toBe(false);
  });

  it('calls unassignFn, never assignFn, when a carried card is dropped back onto the pool', async () => {
    await render();
    fixture.componentInstance.cards.set([CARRIED_PLAN]);
    // As above: the host's own refresh (awaited inside `unassignFn` for
    // real) updates `cards()` before the promise resolves.
    fixture.componentInstance.unassignFn.mockImplementation(async () => {
      fixture.componentInstance.cards.set([{ ...CARRIED_PLAN, courierId: null }]);
      return { applied: true };
    });
    fixture.detectChanges();

    emitDrop(fixture, UNASSIGNED, {
      card: CARRIED_PLAN,
      fromColumnId: 'courier-1',
      toColumnId: UNASSIGNED,
    });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(fixture.componentInstance.unassignFn).toHaveBeenCalledWith(CARRIED_PLAN);
    expect(fixture.componentInstance.assignFn).not.toHaveBeenCalled();
    expect(cardIdsIn(fixture, UNASSIGNED)).toContain('plan-2');
  });

  it('does nothing when a card is dropped back into its own column', async () => {
    await render();

    emitDrop(fixture, UNASSIGNED, { card: PLAN, fromColumnId: UNASSIGNED, toColumnId: UNASSIGNED });
    await flushMicrotasks();

    expect(fixture.componentInstance.assignFn).not.toHaveBeenCalled();
    expect(fixture.componentInstance.unassignFn).not.toHaveBeenCalled();
  });
});
