import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TelegramWebappService } from './telegram-webapp.service';

type TelegramWindow = { Telegram?: { WebApp?: { initDataUnsafe?: { start_param?: string } } } };

describe('TelegramWebappService.startParam (ADR 0183)', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/');
    delete (window as unknown as TelegramWindow).Telegram;
  });

  afterEach(() => {
    window.history.replaceState(null, '', '/');
    delete (window as unknown as TelegramWindow).Telegram;
  });

  function service(): TelegramWebappService {
    TestBed.resetTestingModule();
    return TestBed.inject(TelegramWebappService);
  }

  it('is null for an ordinary visit and for a Mini App opened from the menu button', () => {
    expect(service().startParam).toBeNull();

    (window as unknown as TelegramWindow).Telegram = { WebApp: { initDataUnsafe: {} } };
    expect(service().startParam).toBeNull();
  });

  it('reads the parameter from the query string', () => {
    window.history.replaceState(null, '', '/?tgWebAppStartParam=fromQuery1');

    expect(service().startParam).toBe('fromQuery1');
  });

  it('reads the parameter from the URL fragment, where Telegram delivers its launch parameters', () => {
    window.history.replaceState(
      null,
      '',
      '/#tgWebAppData=query_id%3DAA&tgWebAppVersion=8.0&tgWebAppStartParam=fromHash01',
    );

    expect(service().startParam).toBe('fromHash01');
  });

  it('falls back to Telegram.WebApp.initDataUnsafe.start_param', () => {
    (window as unknown as TelegramWindow).Telegram = {
      WebApp: { initDataUnsafe: { start_param: 'fromSdk001' } },
    };

    expect(service().startParam).toBe('fromSdk001');
  });

  it('prefers the query string, then the fragment, then the SDK', () => {
    (window as unknown as TelegramWindow).Telegram = {
      WebApp: { initDataUnsafe: { start_param: 'fromSdk001' } },
    };
    window.history.replaceState(
      null,
      '',
      '/?tgWebAppStartParam=fromQuery1#tgWebAppStartParam=fromHash01',
    );
    expect(service().startParam).toBe('fromQuery1');

    window.history.replaceState(null, '', '/#tgWebAppStartParam=fromHash01');
    expect(service().startParam).toBe('fromHash01');
  });
});
