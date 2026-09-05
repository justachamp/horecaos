import { TestBed } from '@angular/core/testing';

import { TelegramLinkService } from './telegram-link.service';
import { TelegramLinkApi, type TelegramLinkCode } from '../core/api/telegram-link-api';
import { HorecaOSApiError } from '../core/api/problem-details';

class FakeTelegramLinkApi {
  issueCode = vi.fn();
  status = vi.fn();
  unlink = vi.fn();
}

function setUp(): { service: TelegramLinkService; api: FakeTelegramLinkApi } {
  const api = new FakeTelegramLinkApi();
  TestBed.configureTestingModule({ providers: [{ provide: TelegramLinkApi, useValue: api }] });
  return { service: TestBed.inject(TelegramLinkService), api };
}

function code(): TelegramLinkCode {
  return { code: 'abc123', deepLink: 'https://t.me/jizbiz_bot?start=abc123' };
}

function notFound(): HorecaOSApiError {
  return new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no account' });
}

describe('TelegramLinkService.refresh', () => {
  it('is null before the first read', () => {
    const { service } = setUp();

    expect(service.linked()).toBeNull();
  });

  it('reads the platform status and sets linked accordingly', async () => {
    const { service, api } = setUp();
    api.status.mockResolvedValue({ linked: true });

    const result = await service.refresh();

    expect(result).toBe(true);
    expect(service.linked()).toBe(true);
  });

  it('treats a guest -- signed in, no account at this brand yet -- as unlinked, not a failure', async () => {
    const { service, api } = setUp();
    api.status.mockRejectedValue(notFound());

    const result = await service.refresh();

    expect(result).toBe(false);
    expect(service.linked()).toBe(false);
  });

  it('rethrows a failure that is not not-found -- an unauthenticated or network failure is not a "no account" state', async () => {
    const { service, api } = setUp();
    const failure = new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' });
    api.status.mockRejectedValue(failure);

    await expect(service.refresh()).rejects.toBe(failure);
    expect(service.linked()).toBeNull();
  });

  it('clears a pending code once the read reports linked -- the wait this screen cares about is over', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValue(code());
    await service.mintCode();
    expect(service.pendingCode()).not.toBeNull();

    api.status.mockResolvedValue({ linked: true });
    await service.refresh();

    expect(service.pendingCode()).toBeNull();
  });

  it('leaves a pending code alone while still unlinked -- a poll tick must not wipe the deep link out from under the customer', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValue(code());
    await service.mintCode();

    api.status.mockResolvedValue({ linked: false });
    await service.refresh();

    expect(service.pendingCode()).not.toBeNull();
  });
});

describe('TelegramLinkService.mintCode', () => {
  it('holds the minted code as pendingCode and returns it', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValue(code());

    const result = await service.mintCode();

    expect(result).toEqual(code());
    expect(service.pendingCode()).toEqual(code());
  });

  it('sends a generated idempotency key', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValue(code());

    await service.mintCode();

    expect(api.issueCode).toHaveBeenCalledWith(expect.any(String));
    expect(api.issueCode.mock.calls[0][0]).toBeTruthy();
  });

  it('a second mint replaces the pending code shown to the customer', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValueOnce(code());
    await service.mintCode();

    const secondCode: TelegramLinkCode = { code: 'zzz999', deepLink: 'https://t.me/jizbiz_bot?start=zzz999' };
    api.issueCode.mockResolvedValueOnce(secondCode);
    await service.mintCode();

    expect(service.pendingCode()).toEqual(secondCode);
  });
});

describe('TelegramLinkService.unlink', () => {
  it('sets linked to false and drops any pending code', async () => {
    const { service, api } = setUp();
    api.status.mockResolvedValue({ linked: true });
    await service.refresh();
    api.unlink.mockResolvedValue(undefined);

    await service.unlink();

    expect(service.linked()).toBe(false);
    expect(service.pendingCode()).toBeNull();
  });

  it('propagates a failure and leaves the state untouched', async () => {
    const { service, api } = setUp();
    api.status.mockResolvedValue({ linked: true });
    await service.refresh();
    const failure = new Error('network exploded');
    api.unlink.mockRejectedValue(failure);

    await expect(service.unlink()).rejects.toBe(failure);
    expect(service.linked()).toBe(true);
  });
});

describe('TelegramLinkService.discardPendingCode / forget', () => {
  it('discardPendingCode clears the pending code without calling the platform', async () => {
    const { service, api } = setUp();
    api.issueCode.mockResolvedValue(code());
    await service.mintCode();

    service.discardPendingCode();

    expect(service.pendingCode()).toBeNull();
    expect(api.unlink).not.toHaveBeenCalled();
  });

  it('forget resets both linked and pendingCode -- the next customer on this handset inherits nothing', async () => {
    const { service, api } = setUp();
    api.status.mockResolvedValue({ linked: true });
    await service.refresh();
    api.issueCode.mockResolvedValue(code());
    await service.mintCode();

    service.forget();

    expect(service.linked()).toBeNull();
    expect(service.pendingCode()).toBeNull();
  });
});
