import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { PinKeypad } from './pin-keypad';

describe('PinKeypad', () => {
  let fixture: ComponentFixture<PinKeypad>;

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);
  const press = (...digits: string[]): void => {
    for (const digit of digits) {
      (byId(`pin-key-${digit}`) as HTMLButtonElement).click();
    }
    fixture.detectChanges();
  };

  function render(inputs: Record<string, unknown> = {}): void {
    TestBed.configureTestingModule({ imports: [PinKeypad] });
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PinKeypad);
    fixture.componentRef.setInput('title', 'Enter your PIN');
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
  }

  it('shows dots for a PIN and never the digits', () => {
    render();
    press('4', '8', '2');

    expect(byId('pin-entry')?.textContent).toContain('•••');
    expect(byId('pin-entry')?.textContent).not.toContain('482');
  });

  it('shows the digits of an activation code, which is read off a slip', () => {
    render({ reveal: true, minLength: 8, maxLength: 8 });
    press('1', '2', '3', '4');

    expect(byId('pin-entry')?.textContent).toContain('1234');
  });

  it('keeps OK disabled until the entry is long enough, and never submits by itself at a length', () => {
    render({ minLength: 4, maxLength: 6 });
    const submitted = vi.fn();
    fixture.componentInstance.submitted.subscribe(submitted);

    press('4', '8', '2');
    expect((byId('pin-submit') as HTMLButtonElement).disabled).toBe(true);
    press('9');
    expect((byId('pin-submit') as HTMLButtonElement).disabled).toBe(false);
    press('1', '5');
    expect(submitted).not.toHaveBeenCalled();
  });

  it('stops at the longest length and takes a digit back', () => {
    render({ minLength: 4, maxLength: 4 });
    press('1', '2', '3', '4', '5');
    expect(byId('pin-entry')?.textContent).toContain('••••');
    expect(byId('pin-entry')?.textContent).not.toContain('•••••');

    (byId('pin-back') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(byId('pin-entry')?.textContent).toContain('•••');
    expect(byId('pin-entry')?.textContent).not.toContain('••••');

    (byId('pin-clear') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(byId('pin-entry')?.textContent).not.toContain('•');
  });

  it('hands the digits over once, on OK, and clears the entry as it does', () => {
    render({ minLength: 4, maxLength: 6 });
    const submitted = vi.fn();
    fixture.componentInstance.submitted.subscribe(submitted);

    press('4', '8', '2', '9', '1', '5');
    (byId('pin-submit') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(submitted).toHaveBeenCalledTimes(1);
    expect(submitted).toHaveBeenCalledWith('482915');
    expect(byId('pin-entry')?.textContent).not.toContain('•');
  });

  it('ignores keys while busy and shows the error it is given', () => {
    render({ busy: true, error: 'That PIN did not work.' });
    press('1');

    expect(byId('pin-entry')?.textContent).not.toContain('•');
    expect(byId('pin-error')?.textContent).toContain('That PIN did not work.');
  });
});
