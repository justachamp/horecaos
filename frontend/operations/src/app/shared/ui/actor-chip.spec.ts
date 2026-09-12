import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { ActorChip } from './actor-chip';

function render(kind: string): ReturnType<typeof TestBed.createComponent<ActorChip>> {
  const fixture = TestBed.createComponent(ActorChip);
  fixture.componentRef.setInput('kind', kind);
  fixture.detectChanges();
  return fixture;
}

function label(
  fixture: ReturnType<typeof TestBed.createComponent<ActorChip>>,
): string | null | undefined {
  return (fixture.nativeElement as HTMLElement)
    .querySelector('[data-testid="q-actor-chip-label"]')
    ?.textContent?.trim();
}

function markerClass(fixture: ReturnType<typeof TestBed.createComponent<ActorChip>>): string {
  return (
    (fixture.nativeElement as HTMLElement).querySelector('[data-testid="q-actor-chip-marker"]')
      ?.className ?? ''
  );
}

describe('ActorChip', () => {
  it('renders a resolved human name for a USER principal', () => {
    const fixture = render('USER');
    fixture.componentRef.setInput('displayName', 'Aziza Karimova');
    fixture.componentRef.setInput('subject', 'f4c2…');
    fixture.detectChanges();

    expect(label(fixture)).toBe('Aziza Karimova');
    expect(markerClass(fixture)).toContain('q-actor-chip__marker--user');
  });

  it('falls back to the raw subject when no display name resolved', () => {
    // §11.1's own gap: `actor_display` is null on most rows today.
    const fixture = render('USER');
    fixture.componentRef.setInput('subject', 'a1b2c3d4-e5f6-…');
    fixture.detectChanges();

    expect(label(fixture)).toBe('a1b2c3d4-e5f6-…');
  });

  it('falls back to the unknown-label placeholder when neither is present', () => {
    const fixture = render('SYSTEM_JOB');
    fixture.detectChanges();

    expect(label(fixture)).toBe('—');
  });

  it('marks a SERVICE principal distinctly from a USER one', () => {
    const fixture = render('SERVICE');
    fixture.componentRef.setInput('subject', 'click-provider');
    fixture.detectChanges();

    expect(markerClass(fixture)).toContain('q-actor-chip__marker--service');
    expect(markerClass(fixture)).not.toContain('q-actor-chip__marker--user');
  });

  it('marks a SYSTEM_JOB principal distinctly', () => {
    const fixture = render('SYSTEM_JOB');
    fixture.componentRef.setInput('subject', 'CartRetentionSweeper');
    fixture.detectChanges();

    expect(markerClass(fixture)).toContain('q-actor-chip__marker--system_job');
  });

  it('marks a MIGRATION principal distinctly, never mistaken for a person', () => {
    const fixture = render('MIGRATION');
    fixture.componentRef.setInput('subject', 'backfill-2026-08-30');
    fixture.detectChanges();

    expect(markerClass(fixture)).toContain('q-actor-chip__marker--migration');
    expect(markerClass(fixture)).not.toContain('q-actor-chip__marker--user');
  });

  it('renders an unrecognised wire kind honestly rather than throwing it away', () => {
    // The same forward-compatibility rule `orderStatusLabel` and `triggerLabel`
    // already follow for a wire value this client has not learned yet.
    const fixture = render('SUPPORT_IMPERSONATION');
    fixture.componentRef.setInput('displayName', 'Support (on behalf of Aziza)');
    fixture.detectChanges();

    expect(label(fixture)).toBe('Support (on behalf of Aziza)');
    expect(markerClass(fixture)).toContain('q-actor-chip__marker--support_impersonation');
  });

  it('hides the marker from assistive technology so the label alone carries the meaning', () => {
    const fixture = render('USER');
    fixture.componentRef.setInput('displayName', 'Aziza Karimova');
    fixture.detectChanges();

    const marker = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="q-actor-chip-marker"]',
    );
    expect(marker?.getAttribute('aria-hidden')).toBe('true');
  });
});
