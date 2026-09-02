import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ConnectProviderPanel, ConnectSubmission } from './connect-provider-panel';
import { ProviderConnectDeclaration } from './integrations-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

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
    { key: 'serviceId', secret: false },
    { key: 'secretKey', secret: true },
  ],
};

describe('ConnectProviderPanel', () => {
  let fixture: ComponentFixture<ConnectProviderPanel>;

  beforeEach(async () => {
    localStorage.setItem('horecaos.control-plane.locale', 'en');

    await TestBed.configureTestingModule({
      imports: [ConnectProviderPanel],
      providers: [{ provide: APP_CONFIG, useValue: CONFIG }],
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectProviderPanel);
    fixture.componentRef.setInput('providers', [TELEGRAM, CLICK]);
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
      button.textContent?.trim() === 'Connect',
    ) as HTMLButtonElement;
  }

  async function selectProvider(providerType: string): Promise<void> {
    const select = fixture.nativeElement.querySelector('#connect-provider') as HTMLSelectElement;
    select.value = providerType;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('renders one input per field the selected provider declares, humanising the key', async () => {
    await selectProvider('CLICK');

    expect(fixture.nativeElement.querySelector('#connect-field-merchantId')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#connect-field-serviceId')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('#connect-field-secretKey')).toBeTruthy();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Merchant Id');
  });

  it('renders the declared secret field as type="password" and a non-secret field as type="text"', async () => {
    await selectProvider('CLICK');

    expect((fixture.nativeElement.querySelector('#connect-field-secretKey') as HTMLInputElement).type).toBe(
      'password',
    );
    expect((fixture.nativeElement.querySelector('#connect-field-merchantId') as HTMLInputElement).type).toBe('text');
  });

  it('disables submit until the required fields and the declared secret field are filled', async () => {
    await selectProvider('TELEGRAM_BOT_API');
    expect(submitButton().disabled).toBe(true);

    typeInto('connect-display-name', 'Pilot bot');
    typeInto('connect-environment', 'telegram-prod');
    expect(submitButton().disabled).toBe(true);

    typeInto('connect-field-botToken', 'a-bot-token');
    expect(submitButton().disabled).toBe(false);
  });

  it('emits the secret field value separately from the joined non-secret fields', async () => {
    const handler = vi.fn<(submission: ConnectSubmission) => void>();
    fixture.componentInstance.connect.subscribe(handler);

    await selectProvider('CLICK');
    typeInto('connect-display-name', 'Click sandbox');
    typeInto('connect-environment', 'click-sandbox');
    typeInto('connect-field-merchantId', '12345');
    typeInto('connect-field-serviceId', '67890');
    typeInto('connect-field-secretKey', 'a-click-secret-key');

    submitButton().click();

    expect(handler).toHaveBeenCalledWith({
      providerType: 'CLICK',
      category: 'PAYMENT',
      displayName: 'Click sandbox',
      environmentCode: 'click-sandbox',
      reference: '12345/67890',
      secretValue: 'a-click-secret-key',
    });
  });

  it('emits cancel on the cancel button', () => {
    const handler = vi.fn();
    fixture.componentInstance.cancel.subscribe(handler);

    const cancelButton = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.includes('Cancel'),
    ) as HTMLButtonElement;
    cancelButton.click();

    expect(handler).toHaveBeenCalledOnce();
  });
});
