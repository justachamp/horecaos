import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LegalEntityView } from '../fiscalization/fiscalization-api';
import { I18n } from '../../../core/i18n/i18n';
import { InstallationView } from './integrations-api';
import {
  IntegrationBindingOption,
  RegisterBindingSubmission,
  RegisterMerchantBindingPanel,
} from './register-merchant-binding-panel';

const ACTIVE_ENTITY: LegalEntityView = {
  id: 'legal-1',
  code: 'MAIN',
  legalName: 'Rayhon LLC',
  shortName: null,
  tin: '123456789',
  vatRegistered: true,
  vatCertificateReference: null,
  taxProfileId: null,
  registeredAddress: null,
  contactPhone: null,
  status: 'ACTIVE',
  version: 1,
};

const DRAFT_ENTITY: LegalEntityView = {
  ...ACTIVE_ENTITY,
  id: 'legal-draft',
  legalName: 'Not Yet Active LLC',
  status: 'DRAFT',
};

const CLICK_INSTALLATION: InstallationView = {
  id: 'inst-2',
  category: 'PAYMENT',
  providerType: 'CLICK',
  environmentCode: 'click-prod',
  displayName: 'Click production',
  status: 'ACTIVE',
  secretReference: null,
  lastConnectionStatus: null,
  adapterVersion: null,
  lastSecretRotatedAt: null,
};

const CLICK_INSTALLATION_TWO: InstallationView = {
  ...CLICK_INSTALLATION,
  id: 'inst-2b',
  displayName: 'Click, second branch',
};

const PAYME_INSTALLATION: InstallationView = {
  ...CLICK_INSTALLATION,
  id: 'inst-3',
  providerType: 'PAYME',
  displayName: 'Payme production',
};

const CLICK_BINDING: IntegrationBindingOption = {
  id: 'integration-binding-1',
  installationId: 'inst-2',
  label: 'CLICK · Rayhon Chilonzor',
};

const CLICK_BINDING_TWO: IntegrationBindingOption = {
  id: 'integration-binding-1b',
  installationId: 'inst-2b',
  label: 'CLICK · Rayhon Yakkasaroy',
};

const OTHER_INSTALLATION_BINDING: IntegrationBindingOption = {
  id: 'integration-binding-2',
  installationId: 'inst-3',
  label: 'PAYME · Rayhon Yunusobod',
};

/**
 * As with the other two integration panels, `integrations-page.spec.ts`
 * exercises the *wiring* from a `register` event to `writeSecret`-then-
 * `registerMerchantBinding` by emitting directly on the component instance —
 * it never drives this panel's own fields, so it cannot catch a broken
 * `canSubmit()` gate or a picker that offers the wrong options. This file
 * drives the real inputs.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('RegisterMerchantBindingPanel', () => {
  let fixture: ComponentFixture<RegisterMerchantBindingPanel>;

  async function render(options: {
    legalEntities?: readonly LegalEntityView[];
    installations?: readonly InstallationView[];
    bindings?: readonly IntegrationBindingOption[];
  } = {}): Promise<void> {
    // Reset first: the outer `beforeEach` already rendered a default fixture
    // before a test's own body calls this again with different options —
    // same idiom `integrations-page.spec.ts`'s own denied-state test uses to
    // reconfigure mid-test.
    await TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [RegisterMerchantBindingPanel],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(RegisterMerchantBindingPanel);
    fixture.componentRef.setInput(
      'legalEntities',
      options.legalEntities ?? [ACTIVE_ENTITY, DRAFT_ENTITY],
    );
    fixture.componentRef.setInput(
      'installations',
      options.installations ?? [CLICK_INSTALLATION, CLICK_INSTALLATION_TWO, PAYME_INSTALLATION],
    );
    fixture.componentRef.setInput(
      'bindings',
      options.bindings ?? [CLICK_BINDING, CLICK_BINDING_TWO, OTHER_INSTALLATION_BINDING],
    );
    fixture.detectChanges();
    await flushMicrotasks();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function submitButton(): HTMLButtonElement {
    return host().querySelector('.primary') as HTMLButtonElement;
  }

  function select(id: string): HTMLSelectElement {
    return host().querySelector(id) as HTMLSelectElement;
  }

  function choose(id: string, value: string): void {
    const el = select(id);
    el.value = value;
    el.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function type(id: string, value: string): void {
    const el = host().querySelector(id) as HTMLInputElement;
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  /** Picks every field to a value that satisfies canSubmit(), then lets a test corrupt one. */
  function fillCompleteForm(): void {
    choose('#rmb-legal-entity', 'legal-1');
    choose('#rmb-installation', 'inst-2');
    choose('#rmb-binding', 'integration-binding-1');
    type('#rmb-account', 'svc-2');
    type('#rmb-callback', 'seg-9876543'); // 11 chars, well over the 8-char floor
    type('#rmb-value', 'a-click-secret');
  }

  beforeEach(async () => {
    await render();
  });

  it('starts with the submit button disabled — nothing is chosen yet', () => {
    expect(submitButton().disabled).toBe(true);
  });

  it('defaults the provider to CLICK', () => {
    expect(select('#rmb-provider').value).toBe('CLICK');
  });

  it('renders exactly the active legal entities as options, never the draft one', () => {
    const options = Array.from(select('#rmb-legal-entity').options).map((option) => option.value);
    expect(options).toContain('legal-1');
    expect(options).not.toContain('legal-draft');
  });

  it('renders only installations matching the selected provider type', () => {
    const options = Array.from(select('#rmb-installation').options).map((option) => option.value);
    expect(options).toContain('inst-2');
    expect(options).not.toContain('inst-3');
  });

  it('renders only bindings belonging to the selected installation', () => {
    choose('#rmb-installation', 'inst-2');
    const options = Array.from(select('#rmb-binding').options).map((option) => option.value);
    expect(options).toContain('integration-binding-1');
    expect(options).not.toContain('integration-binding-2');
  });

  it('shows a binding option by its human label, not its raw id', () => {
    choose('#rmb-installation', 'inst-2');
    const option = Array.from(select('#rmb-binding').options).find(
      (candidate) => candidate.value === 'integration-binding-1',
    );
    expect(option?.textContent).toContain('CLICK · Rayhon Chilonzor');
  });

  it('emits register with exactly the chosen ids once the form is complete', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();

    expect(submitButton().disabled).toBe(false);
    submitButton().click();

    expect(register).toHaveBeenCalledWith({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-2',
      integrationBindingId: 'integration-binding-1',
      merchantAccountReference: 'svc-2',
      callbackPathSegment: 'seg-9876543',
      secretValue: 'a-click-secret',
    } satisfies RegisterBindingSubmission);
  });

  it('switches to PAYME, narrows the installation and binding pickers, and carries the provider through', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);

    choose('#rmb-legal-entity', 'legal-1');
    choose('#rmb-provider', 'PAYME');
    expect(
      Array.from(select('#rmb-installation').options).map((option) => option.value),
    ).not.toContain('inst-2');

    choose('#rmb-installation', 'inst-3');
    choose('#rmb-binding', 'integration-binding-2');
    type('#rmb-account', 'svc-2');
    type('#rmb-callback', 'seg-9876543');
    type('#rmb-value', 'a-payme-secret');

    submitButton().click();
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({
        providerType: 'PAYME',
        installationId: 'inst-3',
        integrationBindingId: 'integration-binding-2',
      }),
    );
  });

  it('clears the installation and binding choice when the provider changes, so a stale pick cannot ride along', () => {
    choose('#rmb-installation', 'inst-2');
    choose('#rmb-binding', 'integration-binding-1');

    choose('#rmb-provider', 'PAYME');

    expect(select('#rmb-installation').value).toBe('');
    expect(select('#rmb-binding').value).toBe('');
  });

  it('clears the binding choice when the installation changes, so a binding for a different installation cannot ride along', () => {
    choose('#rmb-installation', 'inst-2');
    choose('#rmb-binding', 'integration-binding-1');

    // Two CLICK installations, each with their own binding: switching must
    // not leave the first installation's binding id sitting in the field.
    choose('#rmb-installation', 'inst-2b');

    expect(select('#rmb-binding').value).toBe('');
    const options = Array.from(select('#rmb-binding').options).map((option) => option.value);
    expect(options).toContain('integration-binding-1b');
    expect(options).not.toContain('integration-binding-1');
  });

  it('disables the binding picker until an installation is chosen', () => {
    expect(select('#rmb-binding').disabled).toBe(true);
    choose('#rmb-installation', 'inst-2');
    expect(select('#rmb-binding').disabled).toBe(false);
  });

  const requiredPickers: ReadonlyArray<readonly [string, string]> = [
    ['#rmb-legal-entity', 'legal-1'],
    ['#rmb-installation', 'inst-2'],
    ['#rmb-binding', 'integration-binding-1'],
  ];

  for (const [selector] of requiredPickers) {
    it(`never emits register while ${selector} is left unchosen, even with everything else filled`, () => {
      const register = vi.fn();
      fixture.componentRef.instance.register.subscribe(register);
      fillCompleteForm();
      choose(selector, '');

      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      expect(register).not.toHaveBeenCalled();
    });
  }

  const requiredTextFields: ReadonlyArray<readonly [string, string]> = [
    ['#rmb-account', 'svc-2'],
    ['#rmb-value', 'a-click-secret'],
  ];

  for (const [selector] of requiredTextFields) {
    it(`never emits register while ${selector} is left blank, even with everything else filled`, () => {
      const register = vi.fn();
      fixture.componentRef.instance.register.subscribe(register);
      fillCompleteForm();
      type(selector, '');

      expect(submitButton().disabled).toBe(true);
      submitButton().click();
      expect(register).not.toHaveBeenCalled();
    });
  }

  it('never emits register while the callback path segment is under the 8-character floor', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();
    type('#rmb-callback', 'short'); // 5 chars — one of the platform's real callback-collision guards

    expect(submitButton().disabled).toBe(true);
    submitButton().click();
    expect(register).not.toHaveBeenCalled();
  });

  it('accepts a callback path segment at exactly the 8-character floor — the boundary itself is valid', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();
    type('#rmb-callback', '12345678'); // exactly 8

    expect(submitButton().disabled).toBe(false);
    submitButton().click();
    expect(register).toHaveBeenCalledWith(
      expect.objectContaining({ callbackPathSegment: '12345678' }),
    );
  });

  it('trims whitespace from the free-text fields before emitting, but never from a chosen id', () => {
    const register = vi.fn();
    fixture.componentRef.instance.register.subscribe(register);
    fillCompleteForm();
    type('#rmb-account', '  svc-2  ');
    type('#rmb-callback', '  seg-9876543  ');

    submitButton().click();
    expect(register).toHaveBeenCalledWith({
      providerType: 'CLICK',
      legalEntityId: 'legal-1',
      installationId: 'inst-2',
      integrationBindingId: 'integration-binding-1',
      merchantAccountReference: 'svc-2',
      callbackPathSegment: 'seg-9876543',
      secretValue: 'a-click-secret',
    } satisfies RegisterBindingSubmission);
  });

  it('shows the empty-installations hint only once none match the selected provider', async () => {
    await render({ installations: [PAYME_INSTALLATION] });
    expect(host().textContent).toContain('No installations for this provider yet');
  });

  it('shows the no-active-legal-entities hint when every entity is a draft', async () => {
    await render({ legalEntities: [DRAFT_ENTITY] });
    expect(host().textContent).toContain('No active legal entities yet');
  });

  it('shows the no-known-bindings hint once an installation is chosen with none recorded', async () => {
    await render({ bindings: [] });
    choose('#rmb-installation', 'inst-2');

    expect(host().textContent).toContain('No known bindings for this installation yet');
  });

  it('shows no such hint before any installation is chosen', () => {
    expect(host().textContent).not.toContain('No known bindings for this installation yet');
  });

  it('disables the whole form while submitting, so a double-click cannot double-register', () => {
    fillCompleteForm();
    fixture.componentRef.setInput('submitting', true);
    fixture.detectChanges();

    expect(submitButton().disabled).toBe(true);
    expect(select('#rmb-legal-entity').disabled).toBe(true);
  });

  it('surfaces the parent’s error message honestly rather than staying silent', () => {
    fixture.componentRef.setInput('errorMessage', 'Something went wrong.');
    fixture.detectChanges();

    expect(host().querySelector('.error')?.textContent).toContain('Something went wrong.');
  });

  it('emits cancel on the backdrop click and the close button, never on the drawer body', () => {
    const cancel = vi.fn();
    fixture.componentRef.instance.cancel.subscribe(cancel);

    (host().querySelector('.drawer') as HTMLElement).click();
    expect(cancel).not.toHaveBeenCalled();

    (host().querySelector('.close') as HTMLButtonElement).click();
    expect(cancel).toHaveBeenCalledTimes(1);
  });
});
