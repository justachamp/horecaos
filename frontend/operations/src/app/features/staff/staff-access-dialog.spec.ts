import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { StaffAccessDialog } from './staff-access-dialog';

function render(mode: 'suspend' | 'restore' = 'suspend') {
  const fixture = TestBed.createComponent(StaffAccessDialog);
  fixture.componentRef.setInput('mode', mode);
  fixture.componentRef.setInput('principalSubject', 'staff-1');
  fixture.componentRef.setInput('affectedJobCount', 2);
  fixture.detectChanges();
  return fixture;
}

describe('StaffAccessDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('ru');
  });

  it('refuses to confirm with a blank reason', () => {
    const fixture = render();
    const confirmations: Array<{ reason: string }> = [];
    fixture.componentInstance.confirmed.subscribe((c) => confirmations.push(c));

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(confirmations).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('Причина обязательна');
  });

  it('emits the trimmed reason on confirm', () => {
    const fixture = render();
    const confirmations: Array<{ reason: string }> = [];
    fixture.componentInstance.confirmed.subscribe((c) => confirmations.push(c));

    const input = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    input.value = '  Left the company  ';
    input.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(confirmations).toEqual([{ reason: 'Left the company' }]);
  });

  it('shows the ADR 0009 caveat only in suspend mode, not restore', () => {
    const suspend = render('suspend');
    expect(suspend.nativeElement.textContent).toContain('экран будет пустым');

    const restore = render('restore');
    expect(restore.nativeElement.textContent).not.toContain('экран будет пустым');
  });

  it('emits dismiss on cancel', () => {
    const fixture = render();
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...fixture.nativeElement.querySelectorAll('button')].find(
      (b: HTMLButtonElement) => !b.getAttribute('data-testid'),
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });
});
