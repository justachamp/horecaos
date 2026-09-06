import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { BrandView } from '../brand-profile/brand-profile-api';
import { LocationView } from '../locations/locations-api';
import { BindSubmission, ConnectProviderPanel, ConnectSubmission } from './connect-provider-panel';
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

const BRAND_ONE: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'MAIN',
  slug: 'main',
  displayName: 'Rayhon Chilonzor',
  status: 'ACTIVE',
};

const BRAND_TWO: BrandView = {
  id: 'brand-2',
  tenantId: 'tenant-1',
  code: 'SECOND',
  slug: 'second',
  displayName: 'Rayhon Yunusobod',
  status: 'ACTIVE',
};

const LOCATION_ONE: LocationView = {
  id: 'location-1',
  tenantId: 'tenant-1',
  brandId: 'brand-1',
  code: 'L1',
  slug: 'l1',
  displayName: 'Chilonzor branch',
  timezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  addressLine: null,
  district: null,
  city: null,
  landmark: null,
  contactPhone: null,
  latitude: null,
  longitude: null,
  coordinateSource: 'NOT_GEOCODED',
};

const LOCATION_TWO: LocationView = {
  ...LOCATION_ONE,
  id: 'location-2',
  brandId: 'brand-2',
  displayName: 'Yunusobod branch',
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

  /**
   * The bind step (wave 66): reachable once the parent flips `phase` to
   * `'bind'` after a successful connect (`integrations-page.spec.ts` proves
   * that hand-off), and — the thing this suite itself must prove — a real
   * brand/location picker that submits chosen ids, never free text, because
   * there is nowhere in this step's template to type one.
   */
  describe('the bind step', () => {
    async function renderBindStep(
      brands: readonly BrandView[] = [BRAND_ONE, BRAND_TWO],
      locations: readonly LocationView[] = [LOCATION_ONE, LOCATION_TWO],
    ): Promise<void> {
      await TestBed.configureTestingModule({ imports: [ConnectProviderPanel] }).compileComponents();
      TestBed.inject(I18n).setLocale('en');
      fixture = TestBed.createComponent(ConnectProviderPanel);
      fixture.componentRef.setInput('providers', [TELEGRAM, CLICK]);
      fixture.componentRef.setInput('phase', 'bind');
      fixture.componentRef.setInput('brands', brands);
      fixture.componentRef.setInput('locations', locations);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();
    }

    function brandSelect(): HTMLSelectElement {
      return host().querySelector('#connect-bind-brand') as HTMLSelectElement;
    }

    function locationSelect(): HTMLSelectElement {
      return host().querySelector('#connect-bind-location') as HTMLSelectElement;
    }

    function bindSubmitButton(): HTMLButtonElement {
      return host().querySelector('.primary') as HTMLButtonElement;
    }

    it('is reachable: the connect fields are gone and the brand/location pickers render instead', async () => {
      await renderBindStep();
      expect(host().querySelector('#connect-display-name')).toBeNull();
      expect(brandSelect()).not.toBeNull();
      expect(locationSelect()).not.toBeNull();
    });

    it('renders exactly the brands the parent passed in, as options on a real select — never a free-text field', async () => {
      await renderBindStep();
      expect(brandSelect().tagName).toBe('SELECT');
      const options = Array.from(brandSelect().options).map((option) => option.value);
      expect(options).toEqual(['brand-1', 'brand-2']);
    });

    it('pre-selects the first brand and narrows the location list to that brand', async () => {
      await renderBindStep();
      expect(brandSelect().value).toBe('brand-1');
      const locationOptions = Array.from(locationSelect().options).map((option) => option.value);
      // The blank "entire brand" option plus exactly this brand's own location.
      expect(locationOptions).toEqual(['', 'location-1']);
    });

    it('submits the selected brand id, never its display name', async () => {
      const bind = vi.fn();
      await renderBindStep();
      fixture.componentRef.instance.bind.subscribe(bind);

      bindSubmitButton().click();

      expect(bind).toHaveBeenCalledWith({ brandId: 'brand-1', locationId: null } satisfies BindSubmission);
    });

    it('re-narrows the location list and clears the old selection when the brand changes', async () => {
      await renderBindStep();
      locationSelect().value = 'location-1';
      locationSelect().dispatchEvent(new Event('change'));
      fixture.detectChanges();

      brandSelect().value = 'brand-2';
      brandSelect().dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(locationSelect().value).toBe('');
      const locationOptions = Array.from(locationSelect().options).map((option) => option.value);
      expect(locationOptions).toEqual(['', 'location-2']);
    });

    it('submits the chosen location id alongside the brand', async () => {
      const bind = vi.fn();
      await renderBindStep();
      fixture.componentRef.instance.bind.subscribe(bind);

      locationSelect().value = 'location-1';
      locationSelect().dispatchEvent(new Event('change'));
      fixture.detectChanges();

      bindSubmitButton().click();

      expect(bind).toHaveBeenCalledWith({
        brandId: 'brand-1',
        locationId: 'location-1',
      } satisfies BindSubmission);
    });

    it('never emits bind while no brand is available to choose', async () => {
      const bind = vi.fn();
      await renderBindStep([], []);
      fixture.componentRef.instance.bind.subscribe(bind);

      expect(bindSubmitButton().disabled).toBe(true);
      bindSubmitButton().click();
      expect(bind).not.toHaveBeenCalled();
    });

    it('emits cancel (labelled "skip") without ever emitting bind', async () => {
      const bind = vi.fn();
      const cancel = vi.fn();
      await renderBindStep();
      fixture.componentRef.instance.bind.subscribe(bind);
      fixture.componentRef.instance.cancel.subscribe(cancel);

      (host().querySelector('.secondary') as HTMLButtonElement).click();

      expect(cancel).toHaveBeenCalledTimes(1);
      expect(bind).not.toHaveBeenCalled();
    });

    it('disables the bind form while submitting, so a double-click cannot double-bind', async () => {
      await renderBindStep();
      fixture.componentRef.setInput('bindSubmitting', true);
      fixture.detectChanges();

      expect(bindSubmitButton().disabled).toBe(true);
      expect(brandSelect().disabled).toBe(true);
    });

    it("surfaces the parent's bind error honestly", async () => {
      await renderBindStep();
      fixture.componentRef.setInput('bindErrorMessage', 'Binding failed.');
      fixture.detectChanges();

      expect(host().querySelector('.error')?.textContent).toContain('Binding failed.');
    });
  });
});
