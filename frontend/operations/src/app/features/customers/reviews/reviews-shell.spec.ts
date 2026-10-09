import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';

import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { ReviewsShell } from './reviews-shell';

function holding(...held: Capability[]) {
  return { has: (capability: Capability) => held.includes(capability) };
}

describe('ReviewsShell', () => {
  let fixture: ComponentFixture<ReviewsShell>;

  async function render(...held: Capability[]): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ReviewsShell],
      providers: [provideRouter([]), { provide: SessionCapabilities, useValue: holding(...held) }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ReviewsShell);
    fixture.detectChanges();
  }

  function tab(testId: string): Element | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`);
  }

  it('shows a reader of reviews the list and nothing they cannot work', async () => {
    await render('REVIEW_READ');

    expect(tab('reviews-tab-list')).not.toBeNull();
    expect(tab('reviews-tab-recovery')).toBeNull();
    expect(tab('reviews-tab-moderation')).toBeNull();
  });

  it('adds the worklist for whoever holds the case capability', async () => {
    await render('REVIEW_READ', 'RECOVERY_CASE_MANAGE');

    expect(tab('reviews-tab-recovery')?.getAttribute('href')).toBe('/customers/reviews/recovery');
    expect(tab('reviews-tab-moderation')).toBeNull();
  });

  it('adds the moderation queue for whoever may moderate', async () => {
    await render('REVIEW_READ', 'REVIEW_MODERATE');

    expect(tab('reviews-tab-moderation')?.getAttribute('href')).toBe(
      '/customers/reviews/moderation',
    );
    expect(tab('reviews-tab-recovery')).toBeNull();
  });
});

beforeEach(() => TestBed.resetTestingModule());
