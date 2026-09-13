import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Board } from './board';

@Component({
  selector: 'q-test-host',
  imports: [Board],
  template: `<q-board><div data-testid="child">A column</div></q-board>`,
})
class TestHost {}

describe('Board', () => {
  let fixture: ComponentFixture<TestHost>;

  it('projects its content inside a drop-list group', async () => {
    await TestBed.configureTestingModule({ imports: [TestHost] }).compileComponents();
    fixture = TestBed.createComponent(TestHost);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const board = host.querySelector('[data-testid="board"]');
    expect(board).not.toBeNull();
    expect(board?.getAttribute('cdkDropListGroup')).not.toBeNull();
    expect(host.querySelector('[data-testid="child"]')?.textContent).toBe('A column');
  });
});
