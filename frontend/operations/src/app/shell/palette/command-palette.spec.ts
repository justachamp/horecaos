import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationKeyView, ConfigurationPlacementsView } from '../../core/api/configuration';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { ConfigurationApi } from '../../features/settings/configuration-api';
import { CommandPalette } from './command-palette';

/** A key whose screen is outside the Settings rail (Staff), so its breadcrumb is not a rail label. */
const MFA: ConfigurationKeyView = {
  code: 'iam.staff_mfa_requirement',
  valueType: 'String',
  defaultValue: null,
  settableScopes: ['PLATFORM', 'TENANT'],
  owningModule: 'iam',
  explicitNullTerminates: false,
  description: 'Whom the tenant asks for an authenticator code.',
};

/** A key a screen sets (the VAT rate was one until ADR 0177 retired it, so this stands in for any routed key). */
const LATE: ConfigurationKeyView = {
  code: 'ordering.late_order_threshold_minutes',
  valueType: 'Integer',
  defaultValue: 10,
  settableScopes: ['PLATFORM', 'TENANT', 'BRAND', 'LOCATION'],
  owningModule: 'ordering',
  explicitNullTerminates: false,
  description: 'Minutes past the promise at which an order shows as late.',
};

describe('CommandPalette', () => {
  let fixture: ComponentFixture<CommandPalette>;
  let closed: number;
  let held: ReadonlySet<Capability>;
  let api: { keys: ReturnType<typeof vi.fn>; placements: ReturnType<typeof vi.fn> };

  async function render(
    options: {
      held?: readonly Capability[];
      placements?: ConfigurationPlacementsView | Error;
      keys?: readonly ConfigurationKeyView[] | Error;
    } = {},
  ): Promise<void> {
    held = new Set(
      options.held ?? [
        'ORDER_READ',
        'TENANT_READ',
        'TENANT_CONFIGURATION_READ',
        'KITCHEN_TICKET_READ',
      ],
    );
    const keys = options.keys ?? [LATE];
    const placements = options.placements ?? { keys: [], policies: [] };
    api = {
      keys:
        keys instanceof Error ? vi.fn().mockRejectedValue(keys) : vi.fn().mockResolvedValue(keys),
      placements:
        placements instanceof Error
          ? vi.fn().mockRejectedValue(placements)
          : vi.fn().mockResolvedValue(placements),
    };
    await TestBed.configureTestingModule({
      imports: [CommandPalette],
      providers: [
        provideRouter([]),
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.has(c) } },
        { provide: FeatureFlags, useValue: { isOn: () => true } },
        {
          provide: CurrentTenant,
          useValue: { tenantId: () => 't1', ensureLoaded: () => Promise.resolve() },
        },
        {
          provide: CurrentLocation,
          useValue: { scope: () => ({ tenantId: 't1', brandId: 'b1', locationId: 'l1' }) },
        },
        { provide: ConfigurationApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CommandPalette);
    closed = 0;
    fixture.componentInstance.closed.subscribe(() => (closed += 1));
    fixture.detectChanges();
    for (let turn = 0; turn < 3; turn += 1) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const input = (): HTMLInputElement =>
    host().querySelector('[data-testid="command-palette-input"]') as HTMLInputElement;
  const rows = (): HTMLElement[] => [
    ...host().querySelectorAll<HTMLElement>('[data-testid="command-palette-item"]'),
  ];
  const labels = (): string[] =>
    rows().map((row) => row.querySelector('.palette__label')?.textContent?.trim() ?? '');

  function type(text: string): void {
    input().value = text;
    input().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function press(key: string): void {
    input().dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('shows nothing until something is typed, with the box ready', async () => {
    await render();

    expect(rows()).toHaveLength(0);
    expect(host().querySelector('[data-testid="command-palette-empty"]')).toBeNull();
    expect(input().getAttribute('role')).toBe('combobox');
  });

  it('offers the sections the operator may open, and not those the rail would hide', async () => {
    await render({ held: ['ORDER_READ'] });

    type('o');
    expect(labels()).toContain('Orders');

    // The rail hides «Kitchen» from this operator; two Settings screens that merely mention a kitchen stay.
    type('kitchen');
    expect(labels()).not.toContain('Kitchen');

    type('zzzz');
    expect(labels()).toEqual([]);
    expect(host().querySelector('[data-testid="command-palette-empty"]')).not.toBeNull();
  });

  it('offers a section the operator holds, and Enter goes there and closes', async () => {
    await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    type('kitchen');
    press('Enter');

    expect(navigate).toHaveBeenCalledWith(['/kitchen']);
    expect(closed).toBe(1);
  });

  it('moves the highlight with the arrows, round the ends, and Enter takes the highlighted one', async () => {
    await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    type('order');
    const names = labels();
    expect(names.length).toBeGreaterThan(1);
    expect(rows()[0].getAttribute('aria-selected')).toBe('true');

    press('ArrowDown');
    expect(rows()[1].getAttribute('aria-selected')).toBe('true');
    press('ArrowUp');
    press('ArrowUp');
    expect(rows().at(-1)?.getAttribute('aria-selected')).toBe('true');
    expect(input().getAttribute('aria-activedescendant')).toBe(rows().at(-1)?.id);

    press('Enter');
    expect(navigate).toHaveBeenCalledTimes(1);
  });

  it('finds a setting by its name, says where it is, and opens its screen at the level it is set', async () => {
    await render({
      placements: {
        keys: [
          {
            keyCode: LATE.code,
            placements: [
              {
                scopeType: 'BRAND',
                brandId: 'b1',
                brandName: 'Rayhon',
                locationId: null,
                locationName: null,
                explicitNull: false,
                displayValue: '12',
                displayTruncated: false,
                version: 1,
                changedByName: null,
                changedAt: null,
              },
            ],
          },
        ],
        policies: [],
      },
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    type('late-order');

    const row = rows().find((candidate) => candidate.textContent?.includes('Late-order threshold'));
    expect(row?.textContent).toContain('set: brand “Rayhon” (12 min)');
    expect(row?.textContent).toContain('Setting');
    row?.click();
    expect(navigate).toHaveBeenCalledWith(['/settings', 'order-policy'], {
      queryParams: { brand: 'b1' },
    });
  });

  it('names the section of a setting that lives outside the Settings rail, not its raw route', async () => {
    await render({ keys: [MFA] });

    type('two-step');
    const hint = rows()
      .find((candidate) => candidate.textContent?.includes('Staff two-step sign-in'))
      ?.querySelector('.palette__hint')?.textContent;

    expect(hint).toContain('Staff → People');
    expect(hint).not.toContain('/staff');
  });

  it('finds that setting by the name of its section, as the Settings search does', async () => {
    await render({ keys: [MFA] });

    type('Staff → People');

    expect(labels()).toContain('Staff two-step sign-in');
  });

  it('speaks the section in the operator’s language', async () => {
    await render({ keys: [MFA] });
    TestBed.inject(I18n).setLocale('ru');
    fixture.detectChanges();

    type('Сотрудники → Люди');

    expect(labels()).toContain('Двухфакторная защита сотрудников');
  });

  it('does not say a setting is not set when where it is set could not be read', async () => {
    await render({ placements: new Error('timed out') });

    type('late-order');

    const row = rows().find((candidate) => candidate.textContent?.includes('Late-order threshold'));
    expect(row?.textContent).toContain('where it is set: unknown · default 10 min');
    expect(row?.textContent).not.toContain('not set');
  });

  it('does not read the settings for someone who may not read the configuration, and still serves the rest', async () => {
    await render({ held: ['ORDER_READ'] });

    type('late-order');

    expect(api.keys).not.toHaveBeenCalled();
    expect(labels()).not.toContain('Late-order threshold');
  });

  it('keeps working when the settings cannot be read', async () => {
    await render({ keys: new Error('refused'), placements: new Error('refused') });

    type('orders');

    expect(labels()).toContain('Orders');
  });

  it('offers a lookup for an order number, which opens the board filtered by it', async () => {
    await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    type('4819');
    expect(labels()).toEqual(['Find order “4819”']);
    press('Enter');

    expect(navigate).toHaveBeenCalledWith(['/orders'], { queryParams: { tab: 'all', q: '4819' } });
  });

  it('says nothing was found, and Enter then does nothing', async () => {
    await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    type('zzzz nothing');
    press('Enter');

    expect(host().querySelector('[data-testid="command-palette-empty"]')?.textContent).toContain(
      'Nothing found',
    );
    expect(navigate).not.toHaveBeenCalled();
    expect(closed).toBe(0);
  });

  it('closes on Escape, as every dialog does, without going anywhere', async () => {
    await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    document.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );

    expect(closed).toBe(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('speaks the operator’s language', async () => {
    await render();
    TestBed.inject(I18n).setLocale('ru');
    fixture.detectChanges();

    type('4819');

    expect(labels()).toEqual(['Найти заказ «4819»']);
    expect(host().textContent).toContain('Заказ');
  });
});
