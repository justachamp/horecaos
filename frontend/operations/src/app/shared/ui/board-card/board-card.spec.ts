import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { BoardCard } from './board-card';

interface Plan {
  readonly planId: string;
}

@Component({
  selector: 'q-test-host',
  imports: [BoardCard],
  template: `
    <q-board-card [dragData]="plan" [dragDisabled]="disabled()">
      <span data-testid="content">{{ plan.planId }}</span>
    </q-board-card>
  `,
})
class TestHost {
  readonly plan: Plan = { planId: 'plan-1' };
  readonly disabled = signal(false);
}

describe('BoardCard', () => {
  it('projects its content and is draggable by default', async () => {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    const fixture: ComponentFixture<TestHost> = TestBed.createComponent(TestHost);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="content"]')?.textContent).toBe('plan-1');
    const card = host.querySelector('[data-testid="board-card"]');
    expect(card?.classList.contains('q-board-card--disabled')).toBe(false);
  });

  it('marks itself disabled when dragDisabled is set', async () => {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    const fixture: ComponentFixture<TestHost> = TestBed.createComponent(TestHost);
    fixture.componentInstance.disabled.set(true);
    fixture.detectChanges();

    const card = (fixture.nativeElement as HTMLElement).querySelector('[data-testid="board-card"]');
    expect(card?.classList.contains('q-board-card--disabled')).toBe(true);
  });
});
