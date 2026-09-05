import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ConnectProviderPanel, ConnectSubmission } from './connect-provider-panel';
import { ProviderConnectDeclaration } from './integrations-api';

const TELEGRAM: ProviderConnectDeclaration = {
  providerType: 'TELEGRAM_BOT_API',
  category: 'NOTIFICATION',
  fields: [{ key: 'botToken', secret: true }],
};

const CLICK: ProviderConnectDeclaration = {
  providerType: 'CLICK',
  category: 'PAYMENT',
  fields: [
    { key: 'merchantId', secret: false },
    { key: 'secretKey', secret: true },
  ],
};

/**
 * `IntegrationsPage`'s own `integrations-page.spec.ts` proves the page wires
 * a `connect` event to `writeSecret`-then-`install` in the right order — but
 * it does that by calling `.connect.emit(...)` directly on this component's
 * instance, bypassing every bit of validation this panel itself owns. This
 * file exercises the panel's own DOM: the secret field it renders is
 * declaration-driven, and the submit button must never fire before every
 * declared field the provider needs is actually filled in.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('ConnectProviderPanel', () => {
  let fixture: ComponentFixture<ConnectProviderPanel>;

  async function render(
    providers: readonly ProviderConnectDeclaration[] = [TELEGRAM, CLICK],
  ): Promise<void> {
    await TestBed.configureTestingModule({ imports: [ConnectProviderPanel] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ConnectProviderPanel);
    fixture.componentRef.setInput('providers', providers);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function displayNameInput(): HTMLInputElement {
    return host().querySelector('#connect-display-name') as HTMLInputElement;
  }

  function environmentInput(): HTMLInputElement {
    return host().querySelector('#connect-environment') as HTMLInputElement;
  }

  function submitButton(): HTMLButtonElement {
    return host().querySelector('.primary') as HTMLButtonElement;
  }

  function type(el: HTMLInputElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('pre-selects the first declared provider once the microtask queue settles, and renders its own field set', async () => {
    await render();
    const select = host().querySelector('#connect-provider') as HTMLSelectElement;
    expect(select.value).toBe('TELEGRAM_BOT_API');
    expect(host().querySelector('#connect-field-botToken')).not.toBeNull();
  });

  it('starts with the submit button disabled — nothing is filled in yet', async () => {
    await render();
    expect(submitButton().disabled).toBe(true);
  });

  it('never emits connect while the display name is blank', async () => {
    await render();
    const connect = vi.fn();
    fixture.componentRef.instance.connect.subscribe(connect);

    type(environmentInput(), 'telegram-prod');
    type(host().querySelector('#connect-field-botToken') as HTMLInputElement, 'a-real-token');
    expect(submitButton().disabled).toBe(true);

    submitButton().click();
    expect(connect).not.toHaveBeenCalled();
  });

  it('never emits connect while the environment code is blank', async () => {
    await render();
    const connect = vi.fn();
    fixture.componentRef.instance.connect.subscribe(connect);

    type(displayNameInput(), 'Pilot bot');
    type(host().querySelector('#connect-field-botToken') as HTMLInputElement, 'a-real-token');
    expect(submitButton().disabled).toBe(true);

    submitButton().click();
    expect(connect).not.toHaveBeenCalled();
  });

  it('never emits connect while the declared secret field is blank, even with everything else filled', async () => {
    await render();
    const connect = vi.fn();
    fixture.componentRef.instance.connect.subscribe(connect);

    type(displayNameInput(), 'Pilot bot');
    type(environmentInput(), 'telegram-prod');
    // The one field this provider declares (`botToken`) is left empty.
    expect(submitButton().disabled).toBe(true);

    submitButton().click();
    expect(connect).not.toHaveBeenCalled();
  });

  it('emits connect with exactly the typed fields once the declared secret is present', async () => {
    await render();
    const connect = vi.fn();
    fixture.componentRef.instance.connect.subscribe(connect);

    type(displayNameInput(), 'Pilot bot');
    type(environmentInput(), 'telegram-prod');
    type(host().querySelector('#connect-field-botToken') as HTMLInputElement, 'a-real-bot-token');

    expect(submitButton().disabled).toBe(false);
    submitButton().click();

    expect(connect).toHaveBeenCalledWith({
      providerType: 'TELEGRAM_BOT_API',
      category: 'NOTIFICATION',
      displayName: 'Pilot bot',
      environmentCode: 'telegram-prod',
      reference: '',
      secretValue: 'a-real-bot-token',
    } satisfies ConnectSubmission);
  });

  it('never leaks the secret value into "reference" — only non-secret declared fields join it', async () => {
    await render();
    const connect = vi.fn();
    fixture.componentRef.instance.connect.subscribe(connect);

    const select = host().querySelector('#connect-provider') as HTMLSelectElement;
    select.value = 'CLICK';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    type(displayNameInput(), 'Click prod');
    type(environmentInput(), 'click-prod');
    type(host().querySelector('#connect-field-merchantId') as HTMLInputElement, 'merchant-42');
    type(host().querySelector('#connect-field-secretKey') as HTMLInputElement, 'super-secret');

    submitButton().click();

    expect(connect).toHaveBeenCalledWith({
      providerType: 'CLICK',
      category: 'PAYMENT',
      displayName: 'Click prod',
      environmentCode: 'click-prod',
      reference: 'merchant-42',
      secretValue: 'super-secret',
    } satisfies ConnectSubmission);
  });

  it('resets the field values when the provider selection changes, so a stale secret cannot ride along', async () => {
    await render();
    type(host().querySelector('#connect-field-botToken') as HTMLInputElement, 'stale-token');

    const select = host().querySelector('#connect-provider') as HTMLSelectElement;
    select.value = 'CLICK';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // Switching back, the old value must not have survived the round trip.
    select.value = 'TELEGRAM_BOT_API';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect((host().querySelector('#connect-field-botToken') as HTMLInputElement).value).toBe('');
  });

  it('disables the whole form while submitting, so a double-click cannot double-submit', async () => {
    await render();
    fixture.componentRef.setInput('submitting', true);
    type(displayNameInput(), 'Pilot bot');
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(true);
    expect(displayNameInput().disabled).toBe(true);
  });

  it('surfaces the parent’s error message honestly rather than staying silent', async () => {
    await render();
    fixture.componentRef.setInput('errorMessage', 'Something went wrong.');
    fixture.detectChanges();

    expect(host().querySelector('.error')?.textContent).toContain('Something went wrong.');
  });

  it('emits cancel on the backdrop click and on the close button, never on the drawer body', async () => {
    await render();
    const cancel = vi.fn();
    fixture.componentRef.instance.cancel.subscribe(cancel);

    (host().querySelector('.drawer') as HTMLElement).click();
    expect(cancel).not.toHaveBeenCalled();

    (host().querySelector('.close') as HTMLButtonElement).click();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
