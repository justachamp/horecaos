import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { RotateSecretDialog, RotateSecretSubmission } from './rotate-secret-dialog';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

describe('RotateSecretDialog', () => {
  let fixture: ComponentFixture<RotateSecretDialog>;

  beforeEach(async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');

    await TestBed.configureTestingModule({
      imports: [RotateSecretDialog],
      providers: [{ provide: APP_CONFIG, useValue: CONFIG }],
    }).compileComponents();

    fixture = TestBed.createComponent(RotateSecretDialog);
    fixture.detectChanges();
  });

  function typeInto(id: string, value: string): void {
    const input = fixture.nativeElement.querySelector(`#${id}`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function submitButton(): HTMLButtonElement {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Rotate'),
    ) as HTMLButtonElement;
  }

  it('disables submit until both the value and a reason are entered', () => {
    expect(submitButton().disabled).toBe(true);

    typeInto('rotate-value', 'a-new-token');
    expect(submitButton().disabled).toBe(true);

    typeInto('rotate-reason', 'Rotated per BotFather');
    expect(submitButton().disabled).toBe(false);
  });

  it('emits the value and the trimmed reason on submit', () => {
    const handler = vi.fn<(submission: RotateSecretSubmission) => void>();
    fixture.componentInstance.confirm.subscribe(handler);

    typeInto('rotate-value', 'a-new-token');
    typeInto('rotate-reason', '  Rotated per BotFather  ');
    submitButton().click();

    expect(handler).toHaveBeenCalledWith({ value: 'a-new-token', reason: 'Rotated per BotFather' });
  });

  it('emits cancel when the cancel button is clicked', () => {
    const handler = vi.fn();
    fixture.componentInstance.cancel.subscribe(handler);

    const cancelButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Cancel'),
    ) as HTMLButtonElement;
    cancelButton.click();

    expect(handler).toHaveBeenCalledOnce();
  });

  it('shows the unverified-provider notice only when asked to', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('no way to verify');

    fixture.componentRef.setInput('unverifiable', true);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('no way to verify');
  });

  it('never renders a value as plain text -- the field is type="password"', () => {
    const input = fixture.nativeElement.querySelector('#rotate-value') as HTMLInputElement;
    expect(input.type).toBe('password');
  });
});
