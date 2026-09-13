import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { Timeline, TimelineEntry } from './timeline';

const ORDER_LANE_ENTRIES: readonly TimelineEntry[] = [
  {
    id: '1',
    timestamp: '12:03',
    title: 'RECEIVED → CONFIRMED',
    detail: 'Checkout',
    selectable: false,
  },
  {
    id: '3',
    timestamp: '12:07',
    title: 'CONFIRMED → PREPARING',
    detail: 'Kitchen progress',
    gapBefore: 'Missing record 2',
    selectable: false,
  },
];

const AUDIT_LIST_ENTRIES: readonly TimelineEntry[] = [
  {
    id: 'evt-1',
    timestamp: '2026-09-12T08:00:00Z',
    title: 'iam.grants.revoke',
    detail: 'TENANT',
    actor: { kind: 'USER', displayName: 'Aziza Karimova', subject: 'f4c2…' },
    badge: { label: 'SUCCEEDED', tone: 'success' },
    selectable: true,
  },
  {
    id: 'evt-2',
    timestamp: '2026-09-12T08:05:00Z',
    title: 'catalog.offering.set',
    actor: { kind: 'SYSTEM_JOB', displayName: null, subject: 'CartRetentionSweeper' },
    badge: { label: 'FAILED', tone: 'danger' },
    selectable: true,
  },
];

function render(
  entries: readonly TimelineEntry[],
): ReturnType<typeof TestBed.createComponent<Timeline>> {
  const fixture = TestBed.createComponent(Timeline);
  fixture.componentRef.setInput('entries', entries);
  fixture.detectChanges();
  return fixture;
}

function rows(
  fixture: ReturnType<typeof TestBed.createComponent<Timeline>>,
): NodeListOf<HTMLElement> {
  return (fixture.nativeElement as HTMLElement).querySelectorAll(
    '[data-testid="q-timeline-entry"]',
  );
}

describe('Timeline', () => {
  it('renders the empty label when there are no entries', () => {
    const fixture = render([]);
    fixture.componentRef.setInput('emptyLabel', 'Хронология пуста');
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-timeline-empty"]')
        ?.textContent,
    ).toBe('Хронология пуста');
    expect(rows(fixture)).toHaveLength(0);
  });

  it('renders the order detail lane — the hand-rolled lanes this row replaces', () => {
    const fixture = render(ORDER_LANE_ENTRIES);

    expect(rows(fixture)).toHaveLength(2);
    expect(rows(fixture)[0].textContent).toContain('RECEIVED → CONFIRMED');
    expect(rows(fixture)[1].textContent).toContain('CONFIRMED → PREPARING');
  });

  it('renders a gap notice as its own row, never silently dropped', () => {
    // §3.10: "if the sequence has a gap the panel says «пропущена запись N»,
    // because hiding it hides a bug."
    const fixture = render(ORDER_LANE_ENTRIES);

    const gap = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-timeline-gap"]',
    );
    expect(gap?.textContent).toBe('Missing record 2');
  });

  it('renders the audit event list — the same component, a different response', () => {
    const fixture = render(AUDIT_LIST_ENTRIES);

    expect(rows(fixture)).toHaveLength(2);
    expect(rows(fixture)[0].textContent).toContain('iam.grants.revoke');
    expect(rows(fixture)[0].textContent).toContain('Aziza Karimova');
    expect(rows(fixture)[1].textContent).toContain('CartRetentionSweeper');
  });

  it('renders each entry’s actor with q-actor-chip rather than its own markup', () => {
    const fixture = render(AUDIT_LIST_ENTRIES);

    const chips = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="q-actor-chip"]',
    );
    expect(chips).toHaveLength(2);
  });

  it('renders a tone badge for the audit outcome', () => {
    const fixture = render(AUDIT_LIST_ENTRIES);

    const badges = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="q-timeline-badge"]',
    );
    expect(badges[0].textContent).toBe('SUCCEEDED');
    expect(badges[0].className).toContain('q-timeline__badge--success');
    expect(badges[1].className).toContain('q-timeline__badge--danger');
  });

  it('emits select only for a selectable row, and renders a static row as no button at all', () => {
    const fixture = render([...ORDER_LANE_ENTRIES, ...AUDIT_LIST_ENTRIES]);
    const selected: string[] = [];
    fixture.componentInstance.select.subscribe((id) => selected.push(id));

    const rendered = rows(fixture);
    expect(rendered[0].tagName).toBe('DIV');
    expect(rendered[2].tagName).toBe('BUTTON');

    (rendered[2] as HTMLButtonElement).click();
    expect(selected).toEqual(['evt-1']);
  });

  it('marks the selected row so the caller’s own detail panel below it stays legible', () => {
    const fixture = render(AUDIT_LIST_ENTRIES);
    fixture.componentRef.setInput('selectedId', 'evt-1');
    fixture.detectChanges();

    const rendered = rows(fixture);
    expect(rendered[0].className).toContain('q-timeline__entry--selected');
    expect(rendered[0].getAttribute('aria-expanded')).toBe('true');
    expect(rendered[1].className).not.toContain('q-timeline__entry--selected');
  });
});
