import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { TimelineBlock, TimelineResource, TimelineScheduler } from './timeline-scheduler';

const RESOURCES: readonly TimelineResource[] = [
  { id: 't1', label: 'Table 1' },
  { id: 't2', label: 'Table 2' },
];

const BLOCKS: readonly TimelineBlock[] = [
  { id: 'b1', resourceId: 't1', startMinutes: 60, endMinutes: 120, label: 'Ismoilov, 4' },
  { id: 'b2', resourceId: 't2', startMinutes: 90, endMinutes: 150, label: 'Karimova, 2' },
];

describe('TimelineScheduler', () => {
  let fixture: ComponentFixture<TimelineScheduler>;

  function render(
    inputs: {
      resources?: readonly TimelineResource[];
      blocks?: readonly TimelineBlock[];
      pixelsPerMinute?: number;
    } = {},
  ): HTMLElement {
    fixture = TestBed.createComponent(TimelineScheduler);
    fixture.componentRef.setInput('resources', inputs.resources ?? RESOURCES);
    fixture.componentRef.setInput('blocks', inputs.blocks ?? BLOCKS);
    if (inputs.pixelsPerMinute !== undefined) {
      fixture.componentRef.setInput('pixelsPerMinute', inputs.pixelsPerMinute);
    }
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('renders one row per resource', () => {
    const host = render();
    expect(host.querySelector('[data-testid="timeline-row-t1"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="timeline-row-t2"]')).not.toBeNull();
  });

  it('places each block only under its own resource’s row, positioned by its start time', () => {
    const host = render({ pixelsPerMinute: 1 });

    const rowT1 = host.querySelector('[data-testid="timeline-row-t1"]')!;
    const blockInT1 = rowT1.querySelector<HTMLElement>('[data-testid="timeline-block-b1"]')!;
    expect(blockInT1).not.toBeNull();
    expect(blockInT1.style.left).toBe('60px');
    expect(blockInT1.style.width).toBe('60px');
    // b2 belongs to t2, not t1.
    expect(rowT1.querySelector('[data-testid="timeline-block-b2"]')).toBeNull();

    const rowT2 = host.querySelector('[data-testid="timeline-row-t2"]')!;
    expect(rowT2.querySelector('[data-testid="timeline-block-b2"]')).not.toBeNull();
  });

  it('shows nothing under a resource with no bookings, rather than erroring', () => {
    const host = render({ resources: [{ id: 't3', label: 'Table 3' }], blocks: [] });
    const row = host.querySelector('[data-testid="timeline-row-t3"]')!;
    expect(row.querySelectorAll('[data-testid^="timeline-block-"]')).toHaveLength(0);
  });
});
