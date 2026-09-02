import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { StaffInviteDialog } from './staff-invite-dialog';

describe('StaffInviteDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('ru');
  });

  it('names the backend gap rather than offering a form with nothing behind it', () => {
    const fixture = TestBed.createComponent(StaffInviteDialog);
    fixture.detectChanges();

    // No name, phone, or email field — the honest-not-built dialog offers nothing to fill in.
    expect(fixture.nativeElement.querySelectorAll('input, textarea, select')).toHaveLength(0);
  });

  it('emits dismiss on close', () => {
    const fixture = TestBed.createComponent(StaffInviteDialog);
    fixture.detectChanges();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-invite-dialog-close"]',
      ) as HTMLButtonElement
    ).click();

    expect(dismissed).toBe(true);
  });
});
