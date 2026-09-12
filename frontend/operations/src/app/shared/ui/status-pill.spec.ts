import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { StatusPill } from './status-pill';

function render(label: string): ReturnType<typeof TestBed.createComponent<StatusPill>> {
  const fixture = TestBed.createComponent(StatusPill);
  fixture.componentRef.setInput('label', label);
  fixture.detectChanges();
  return fixture;
}

function segment(
  fixture: ReturnType<typeof TestBed.createComponent<StatusPill>>,
  name: string,
): HTMLElement | null {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    `[data-testid="q-status-pill-${name}"]`,
  );
}

describe('StatusPill', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('renders the status word and no overlay by default', () => {
    const fixture = render('Ready');

    expect(segment(fixture, 'status')?.textContent?.trim()).toBe('Ready');
    expect(segment(fixture, 'overlay')).toBeNull();
    expect(segment(fixture, 'secondary')).toBeNull();
  });

  it('renders a lateness overlay on a NON-late status without changing the status word', () => {
    // The whole point of row X.15, and the assertion that fails if lateness is
    // ever folded back into the status: a READY order that has gone late still
    // reads READY. The food is ready; it is the handover that is late.
    const fixture = render('Ready');
    fixture.componentRef.setInput('tone', 'success');
    fixture.componentRef.setInput('overlayLabel', 'late');
    fixture.detectChanges();

    expect(segment(fixture, 'status')?.textContent?.trim()).toBe('Ready');
    expect(segment(fixture, 'overlay')?.textContent?.trim()).toBe('late');
  });

  it('keeps the status tone and the overlay tone independent', () => {
    const fixture = render('Ready');
    fixture.componentRef.setInput('tone', 'success');
    fixture.componentRef.setInput('overlayLabel', 'late');
    fixture.componentRef.setInput('overlayTone', 'danger');
    fixture.detectChanges();

    expect(segment(fixture, 'status')?.className).toContain('q-status-pill__segment--success');
    expect(segment(fixture, 'status')?.className).not.toContain('q-status-pill__segment--danger');
    expect(segment(fixture, 'overlay')?.className).toContain('q-status-pill__segment--danger');
  });

  it('renders the dual-state order+cooking pill as one object', () => {
    const fixture = render('Confirmed');
    fixture.componentRef.setInput('secondaryLabel', 'Cooking');
    fixture.componentRef.setInput('secondaryTone', 'info');
    fixture.detectChanges();

    expect(segment(fixture, 'status')?.textContent?.trim()).toBe('Confirmed');
    expect(segment(fixture, 'secondary')?.textContent?.trim()).toBe('Cooking');
    // One border, one pill: the two segments are siblings inside it, not two
    // separate badges an operator has to correlate.
    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="q-status-pill"]'),
    ).toHaveLength(1);
  });

  it('announces itself as one sentence in reading order, not three fragments', () => {
    const fixture = render('Confirmed');
    fixture.componentRef.setInput('secondaryLabel', 'Cooking');
    fixture.componentRef.setInput('overlayLabel', 'late');
    fixture.detectChanges();

    const pill = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-status-pill"]',
    )!;
    expect(pill.getAttribute('aria-label')).toBe('Confirmed · Cooking · late');
    // Every segment is hidden from the reader, or it would hear the words
    // twice — once composed and once one at a time.
    expect(segment(fixture, 'status')?.getAttribute('aria-hidden')).toBe('true');
    expect(segment(fixture, 'overlay')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('leaves an absent segment out of the announcement rather than announcing a gap', () => {
    const fixture = render('Cancelled');
    const pill = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-status-pill"]',
    )!;

    expect(pill.getAttribute('aria-label')).toBe('Cancelled');
  });

  it('renders an unknown status as the raw value it was handed, never blank', () => {
    // `orderStatusLabel` passes a wire value straight through for a status this
    // client has not learned; the pill must not swallow it.
    const fixture = render('ON_HOLD_FOR_STOCK');

    expect(segment(fixture, 'status')?.textContent?.trim()).toBe('ON_HOLD_FOR_STOCK');
  });
});
