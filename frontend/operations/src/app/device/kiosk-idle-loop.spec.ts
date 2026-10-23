import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { environment } from '../../environments/environment';
import { I18n } from '../core/i18n/i18n';
import { DeviceIdleMedia } from './device-profile';
import { KioskIdleLoop } from './kiosk-idle-loop';

const FIRST: DeviceIdleMedia = {
  id: 'i1',
  assetId: 'a1',
  position: 0,
  url: '/api/v1/storefront/tenants/t1/media/a1',
};
const SECOND: DeviceIdleMedia = {
  id: 'i2',
  assetId: 'a2',
  position: 1,
  url: '/api/v1/storefront/tenants/t1/media/a2',
};

describe('KioskIdleLoop (ADR 0187)', () => {
  let fixture: ComponentFixture<KioskIdleLoop>;

  const image = (): HTMLImageElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="device-kiosk-idle-picture"]',
    );

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({ imports: [KioskIdleLoop] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KioskIdleLoop);
    fixture.componentRef.setInput('picture', FIRST);
    fixture.componentRef.setInput('channelName', 'Lobby channel');
    fixture.componentRef.setInput('branchName', 'Chilanzar');
    fixture.detectChanges();
  }

  it('draws the picture from the platform’s own origin, as a decorative image', async () => {
    await render();

    expect(image()?.getAttribute('src')).toBe(`${environment.apiBaseUrl}${FIRST.url}`);
    expect(image()?.getAttribute('alt')).toBe('');
  });

  it('keeps the channel and the branch in view over the picture', async () => {
    await render();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="device-kiosk-channel"]')?.textContent).toContain(
      'Lobby channel',
    );
    expect(host.querySelector('[data-testid="device-kiosk-branch"]')?.textContent).toContain(
      'Chilanzar',
    );
  });

  it('draws a fresh image element for the next picture, so the cross-fade runs again', async () => {
    await render();
    const before = image();

    fixture.componentRef.setInput('picture', SECOND);
    fixture.detectChanges();

    expect(image()).not.toBe(before);
    expect(image()?.getAttribute('src')).toContain('/media/a2');
  });
});
