import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { I18n } from '../core/i18n/i18n';
import { RosterMember } from './terminal-api';
import { TerminalOutcome, TerminalSession } from './terminal-session';
import { TerminalUnlock } from './terminal-unlock';

const AZIZA: RosterMember = { memberId: 'm1', label: 'Aziza K.', pinState: 'ACTIVE' };
const BAKHROM: RosterMember = { memberId: 'm2', label: 'Bakhrom T.', pinState: 'NOT_SET' };
const DILYA: RosterMember = { memberId: 'm3', label: 'Dilya S.', pinState: 'LOCKED' };

async function flush(): Promise<void> {
  if (vi.isFakeTimers()) {
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(0);
    return;
  }
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('TerminalUnlock (ADR 0155)', () => {
  let fixture: ComponentFixture<TerminalUnlock>;
  let terminal: {
    members: ReturnType<typeof signal<readonly RosterMember[]>>;
    pinLength: ReturnType<typeof signal<number>>;
    lockRequired: ReturnType<typeof signal<boolean>>;
    unlock: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
    changePin: ReturnType<typeof vi.fn>;
  };

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  function tap(...ids: string[]): void {
    for (const id of ids) {
      (byId(id) as HTMLElement).click();
      fixture.detectChanges();
    }
  }
  const digits = (value: string): string[] => [...value].map((digit) => `pin-key-${digit}`);

  async function ok(): Promise<void> {
    tap('pin-submit');
    await flush();
    fixture.detectChanges();
  }

  async function render(
    options: { flow?: 'unlock' | 'change'; required?: boolean; members?: RosterMember[] } = {},
  ): Promise<ReturnType<typeof vi.fn>> {
    terminal = {
      members: signal<readonly RosterMember[]>(options.members ?? [AZIZA, BAKHROM, DILYA]),
      pinLength: signal(6),
      lockRequired: signal(options.required ?? false),
      unlock: vi.fn<() => Promise<TerminalOutcome>>().mockResolvedValue({ kind: 'OK' }),
      activate: vi.fn<() => Promise<TerminalOutcome>>().mockResolvedValue({ kind: 'OK' }),
      changePin: vi.fn<() => Promise<TerminalOutcome>>().mockResolvedValue({ kind: 'OK' }),
    };
    await TestBed.configureTestingModule({
      imports: [TerminalUnlock],
      providers: [{ provide: TerminalSession, useValue: terminal }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(TerminalUnlock);
    if (options.flow) {
      fixture.componentRef.setInput('flow', options.flow);
    }
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return closed;
  }

  it('lists the labels the tenant chose and what each person still has to do, and nothing personal', async () => {
    await render();

    const roster = byId('terminal-roster')?.textContent ?? '';
    expect(roster).toContain('Aziza K.');
    expect(roster).toContain('Set a PIN');
    expect(roster).toContain('Locked');
  });

  it('says so when nobody is set up to unlock this screen', async () => {
    await render({ members: [] });

    expect(byId('terminal-roster-empty')).not.toBeNull();
  });

  it('unlocks with a tap on the name and a PIN, and closes', async () => {
    const closed = await render();

    tap('terminal-person-m1', ...digits('482915'));
    await ok();

    expect(terminal.unlock).toHaveBeenCalledWith('m1', '482915');
    expect(closed).toHaveBeenCalled();
  });

  it('gives the same sentence for every refused PIN and lets the person try again', async () => {
    terminal = undefined as never;
    const closed = await render();
    terminal.unlock.mockResolvedValue({ kind: 'REFUSED' });

    tap('terminal-person-m1', ...digits('000000'));
    await ok();

    expect(byId('pin-error')?.textContent).toContain('That PIN did not work.');
    expect(closed).not.toHaveBeenCalled();
    expect(byId('pin-keypad')).not.toBeNull();
  });

  it('says a lock in minutes, rounded up, and offers no keypad while it lasts', async () => {
    await render();
    terminal.unlock.mockResolvedValue({ kind: 'LOCKED', retryAfterSeconds: 840 });

    tap('terminal-person-m1', ...digits('000000'));
    await ok();

    expect(byId('terminal-locked')?.textContent).toContain('14 min');
    expect(byId('pin-keypad')).toBeNull();
  });

  it('says a credential locked for the day is the manager’s to reset, without a keypad', async () => {
    await render();

    tap('terminal-person-m3');

    expect(byId('terminal-locked')?.textContent).toContain('Ask your manager');
    expect(byId('pin-keypad')).toBeNull();
  });

  it('sets a PIN with a code: the code, the new PIN twice, then one activation call', async () => {
    const closed = await render();

    tap('terminal-person-m2', 'terminal-set-pin', ...digits('12345678'));
    await ok();
    expect(byId('pin-keypad')?.textContent).toContain('Choose a PIN of 6 digits');
    tap(...digits('482915'));
    await ok();
    tap(...digits('482915'));
    await ok();

    expect(terminal.activate).toHaveBeenCalledWith('m2', '12345678', '482915');
    expect(closed).toHaveBeenCalled();
  });

  it('starts again when the two PINs differ, and sends nothing', async () => {
    await render();

    tap('terminal-person-m2', 'terminal-set-pin', ...digits('12345678'));
    await ok();
    tap(...digits('482915'));
    await ok();
    tap(...digits('482916'));
    await ok();

    expect(terminal.activate).not.toHaveBeenCalled();
    expect(byId('pin-error')?.textContent).toContain('differ');
  });

  it('names the rule a new PIN broke and keeps the code good', async () => {
    await render();
    terminal.activate.mockResolvedValue({ kind: 'PIN_RULE', reason: 'SEQUENCE' });

    tap('terminal-person-m2', 'terminal-set-pin', ...digits('12345678'));
    await ok();
    tap(...digits('123456'));
    await ok();
    tap(...digits('123456'));
    await ok();

    expect(byId('pin-error')?.textContent).toContain('1234');
    expect(byId('pin-keypad')?.textContent).toContain('Choose a PIN of 6 digits');
  });

  it('goes back to the code when the code was not accepted', async () => {
    await render();
    terminal.activate.mockResolvedValue({ kind: 'CODE_REFUSED' });

    tap('terminal-person-m2', 'terminal-set-pin', ...digits('12345678'));
    await ok();
    tap(...digits('482915'));
    await ok();
    tap(...digits('482915'));
    await ok();

    expect(byId('pin-error')?.textContent).toContain('code was not accepted');
    expect(byId('pin-keypad')?.textContent).toContain('Enter the activation code');
  });

  it('offers the code from the PIN screen too, for someone who has one and a half-remembered PIN', async () => {
    await render();

    tap('terminal-person-m1', 'terminal-have-code');

    expect(byId('pin-keypad')?.textContent).toContain('Enter the activation code');
  });

  it('cannot be dismissed while a person must be unlocked', async () => {
    await render({ required: true });

    expect(byId('terminal-unlock-close')).toBeNull();
  });

  it('can be dismissed when unlocking is optional', async () => {
    const closed = await render({ required: false });

    tap('terminal-unlock-close');

    expect(closed).toHaveBeenCalled();
  });

  it('changes a PIN with the current one and the new one twice, and says it was changed', async () => {
    vi.useFakeTimers();
    try {
      const closed = await render({ flow: 'change' });
      expect(byId('pin-keypad')?.textContent).toContain('current PIN');

      tap(...digits('482915'));
      await ok();
      tap(...digits('735914'));
      await ok();
      tap(...digits('735914'));
      await ok();

      expect(terminal.changePin).toHaveBeenCalledWith('482915', '735914');
      expect(byId('terminal-done')?.textContent).toContain('changed');
      await vi.advanceTimersByTimeAsync(2_000);
      expect(closed).toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('asks for the current PIN again when it was wrong', async () => {
    await render({ flow: 'change' });
    terminal.changePin.mockResolvedValue({ kind: 'REFUSED' });

    tap(...digits('000000'));
    await ok();
    tap(...digits('735914'));
    await ok();
    tap(...digits('735914'));
    await ok();

    expect(byId('pin-error')?.textContent).toContain('That PIN did not work.');
    expect(byId('pin-keypad')?.textContent).toContain('current PIN');
  });
});
