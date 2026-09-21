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
  // Telegram has exactly one approved environment in production (the real
  // gap-map defect this drawer closes) — the fixture every "exactly one is
  // preselected" test below relies on.
  environments: [{ code: 'telegram-prod', production: true }],
};

const CLICK: ProviderConnectDeclaration = {
  providerType: 'CLICK',
  category: 'PAYMENT',
  // Mirrors ConnectFieldCatalog's own CLICK declaration exactly (merchantId,
  // serviceId, secretKey) — the "leaves serviceId blank and still holds its
  // position" test below depends on there being a second non-secret field to
  // leave blank.
  fields: [
    { key: 'merchantId', secret: false },
    { key: 'serviceId', secret: false },
    { key: 'secretKey', secret: true },
  ],
  // Two approved environments, production listed first (the server's own
  // ordering) — the fixture every "several approved, none preselected" test
  // below relies on.
  environments: [
    { code: 'click-prod', production: true },
    { code: 'click-sandbox', production: false },
  ],
};

const NO_ENVIRONMENTS: ProviderConnectDeclaration = {
  providerType: 'ANALYTICS_GTM',
  category: 'ANALYTICS',
  fields: [{ key: 'gtmContainerId', secret: false }],
  // The platform has not approved anything for this provider yet — a real,
  // renderable state (`ProviderInstallationController.ConnectFieldDeclarationView`'s
  // own doc comment), never a reason to fall back to a free-text field.
  environments: [],
};

const BRAND_ONE: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'MAIN',
  slug: 'main',
  displayName: 'Rayhon Chilonzor',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
};

const BRAND_TWO: BrandView = {
  id: 'brand-2',
  tenantId: 'tenant-1',
  code: 'SECOND',
  slug: 'second',
  displayName: 'Rayhon Yunusobod',
  status: 'ACTIVE',
  contactPhone: null,
  telegramHandle: null,
  logoAssetId: null,
  bannerAssetId: null,
  locales: [],
  version: 0,
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

  function environmentSelect(): HTMLSelectElement | null {
    return host().querySelector('#connect-environment');
  }

  function environmentNoneMessage(): HTMLElement | null {
    return host().querySelector('#connect-environment-none');
  }

  function submitButton(): HTMLButtonElement {
    return host().querySelector('.primary') as HTMLButtonElement;
  }

  function type(el: HTMLInputElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function selectOption(el: HTMLSelectElement, value: string): void {
    el.value = value;
    el.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('pre-selects the first declared provider once the microtask queue settles, and renders its own field set', async () => {
    await render();
    const providerSelect = host().querySelector('#connect-provider') as HTMLSelectElement;
    expect(providerSelect.value).toBe('TELEGRAM_BOT_API');
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

    // Telegram's own environment (exactly one) is already preselected — see
    // "preselects the sole approved environment" below.
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

    const providerSelect = host().querySelector('#connect-provider') as HTMLSelectElement;
    selectOption(providerSelect, 'CLICK');

    type(displayNameInput(), 'Click prod');
    selectOption(environmentSelect() as HTMLSelectElement, 'click-prod');
    type(host().querySelector('#connect-field-merchantId') as HTMLInputElement, 'merchant-42');
    type(host().querySelector('#connect-field-secretKey') as HTMLInputElement, 'super-secret');

    submitButton().click();

    // serviceId is left blank and still holds its position (ADR 0106): the
    // server splits this same string back apart by position against
    // ConnectFieldCatalog's own field order, so a dropped blank field would
    // shift every later field left.
    expect(connect).toHaveBeenCalledWith({
      providerType: 'CLICK',
      category: 'PAYMENT',
      displayName: 'Click prod',
      environmentCode: 'click-prod',
      reference: 'merchant-42/',
      secretValue: 'super-secret',
    } satisfies ConnectSubmission);
  });

  /**
   * The environment picker: fixes the two pre-production "Unknown provider
   * environment" failures (2026-09-19 and 2026-09-21) by replacing the free
   * text this field used to be with a `<select>` built from the
   * declaration's own `environments` — an operator cannot type a code the
   * server will refuse.
   */
  describe('the environment picker', () => {
    it('renders a select of the chosen declaration’s approved environments, production labelled and first', async () => {
      await render();
      const providerSelect = host().querySelector('#connect-provider') as HTMLSelectElement;
      selectOption(providerSelect, 'CLICK');

      const options = Array.from(environmentSelect()?.options ?? []).map((option) => option.value);
      expect(options).toEqual(['', 'click-prod', 'click-sandbox']);
      expect(environmentSelect()?.options[1].textContent).toContain('live');
      expect(environmentSelect()?.options[2].textContent).toContain('test');
    });

    it('preselects the sole approved environment, still a visible, real choice', async () => {
      await render();
      // TELEGRAM (the default provider) declares exactly one environment.
      const options = Array.from(environmentSelect()?.options ?? []).map((option) => option.value);
      expect(options).toEqual(['telegram-prod']);
      expect(environmentSelect()?.value).toBe('telegram-prod');
    });

    it('leaves several approved environments unchosen until the operator picks one', async () => {
      await render();
      const providerSelect = host().querySelector('#connect-provider') as HTMLSelectElement;
      selectOption(providerSelect, 'CLICK');

      expect(environmentSelect()?.value).toBe('');
      expect(submitButton().disabled).toBe(true);
    });

    it('resets the chosen environment when the provider selection changes', async () => {
      await render();
      expect(environmentSelect()?.value).toBe('telegram-prod');

      const providerSelect = host().querySelector('#connect-provider') as HTMLSelectElement;
      selectOption(providerSelect, 'CLICK');
      expect(environmentSelect()?.value).toBe('');

      selectOption(environmentSelect() as HTMLSelectElement, 'click-sandbox');
      expect(environmentSelect()?.value).toBe('click-sandbox');

      // Switching back to Telegram must not carry Click's sandbox choice
      // along, and must re-preselect Telegram's own sole environment.
      selectOption(providerSelect, 'TELEGRAM_BOT_API');
      expect(environmentSelect()?.value).toBe('telegram-prod');
    });

    /**
     * The platform-side half of the defect this drawer closes: a free-text
     * field let an operator submit a guess the server could only refuse,
     * writing a secret through the door first every time (the orphaned-secret
     * failure mode both pre-production incidents shared). With the
     * declaration approving none, there must be nothing to guess into.
     */
    it('shows a clear message and disables submit when the provider has no approved environment, never emitting connect', async () => {
      await render([NO_ENVIRONMENTS]);
      const connect = vi.fn();
      fixture.componentRef.instance.connect.subscribe(connect);

      expect(environmentSelect()).toBeNull();
      expect(environmentNoneMessage()?.textContent).toContain(
        'No environment is approved for this provider yet',
      );

      type(displayNameInput(), 'GTM container');
      type(host().querySelector('#connect-field-gtmContainerId') as HTMLInputElement, 'GTM-ABC1234');
      expect(submitButton().disabled).toBe(true);

      submitButton().click();
      expect(connect).not.toHaveBeenCalled();
    });
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
   * Row `X.33`: this drawer rendered no step indicator at all before
   * `q-steps` existed — an operator on the connect step had no way to tell
   * a bind step followed at all.
   */
  describe('the step indicator', () => {
    function stepItems(): NodeListOf<HTMLElement> {
      return host().querySelectorAll('[data-testid="q-steps-item"]');
    }

    it('renders both steps, with connect current and bind upcoming, on the connect step', async () => {
      await render();

      const steps = stepItems();
      expect(steps).toHaveLength(2);
      expect(steps[0].textContent).toContain('Connect');
      expect(steps[0].className).toContain('q-steps__item--current');
      expect(steps[1].textContent).toContain('Bind');
      expect(steps[1].className).toContain('q-steps__item--upcoming');
    });

    it('marks connect complete and bind current once the drawer moves to the bind step', async () => {
      await TestBed.configureTestingModule({ imports: [ConnectProviderPanel] }).compileComponents();
      TestBed.inject(I18n).setLocale('en');
      fixture = TestBed.createComponent(ConnectProviderPanel);
      fixture.componentRef.setInput('providers', [TELEGRAM, CLICK]);
      fixture.componentRef.setInput('phase', 'bind');
      fixture.componentRef.setInput('brands', [BRAND_ONE]);
      fixture.detectChanges();
      await flushMicrotasks();
      fixture.detectChanges();

      const steps = stepItems();
      expect(steps[0].className).toContain('q-steps__item--complete');
      expect(steps[1].className).toContain('q-steps__item--current');
    });
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

      expect(bind).toHaveBeenCalledWith({
        brandId: 'brand-1',
        locationId: null,
      } satisfies BindSubmission);
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
