import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';
import { Router } from '@angular/router';

import { ConfigurationKeyView, ConfigurationPlacementsView } from '../../core/api/configuration';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { FeatureFlags } from '../../core/feature-flags';
import { I18n } from '../../core/i18n/i18n';
import { ConfigurationApi } from '../../features/settings/configuration-api';
import { FindScope, findSettings } from '../../features/settings/settings-find';
import { SETTINGS_NAVIGATION, visibleSettings } from '../../features/settings/settings-nav';
import { Modal } from '../../shared/ui/modal';
import { NAV_ITEMS, admitsSection } from '../navigation';
import { Destination, PaletteItem, paletteItems } from './palette-items';
import { paletteText } from './palette-text';

/**
 * The console's quick jump (`⌘K` / `Ctrl+K`, settings.md §1.6): one box over the sections the operator may open,
 * the Settings screens, «Find a setting» and the order board's number search. Type, arrow, Enter.
 *
 * It offers only places the operator may go: the rail's own capability filter and Settings' own (`admitsSection`,
 * `visibleSettings`) decide what is listed, so the palette can never be a way into a section the rail hides --
 * and the route guards still refuse a direct URL, as they refuse any other.
 *
 * It is a modal (`q-modal`: Escape, focus trap, focus restore), opened by the shell and loaded the first time it
 * is, so it costs the first paint nothing. The settings keys and where they are set are read when it opens, best
 * effort: without them the sections and orders still work.
 */
@Component({
  selector: 'q-command-palette',
  imports: [Modal],
  templateUrl: './command-palette.html',
  styleUrl: './command-palette.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CommandPalette implements OnInit {
  /** The palette was dismissed or an item was chosen. */
  readonly closed = output<void>();

  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly flags = inject(FeatureFlags);
  private readonly tenant = inject(CurrentTenant);
  private readonly location = inject(CurrentLocation);
  private readonly configurationApi = inject(ConfigurationApi);

  protected readonly query = signal('');
  protected readonly activeIndex = signal(0);
  private readonly keys = signal<readonly ConfigurationKeyView[]>([]);
  private readonly placements = signal<ConfigurationPlacementsView | null>(null);

  protected readonly listId = 'command-palette-list';

  private readonly scope = computed<FindScope>(() => {
    const here = this.location.scope();
    return { brandId: here?.brandId ?? null, locationId: here?.locationId ?? null };
  });

  /** Every place the operator may go, named in their language: the rail's sections, then Settings' screens. */
  private readonly destinations = computed<readonly Destination[]>(() => {
    const held = (capability: Parameters<SessionCapabilities['has']>[0]): boolean =>
      this.capabilities.has(capability);
    const sections = NAV_ITEMS.filter((item) => admitsSection(item, held)).map(
      (item): Destination => ({
        id: `go:${item.path}`,
        label: this.i18n.t(item.label),
        hint: this.text('go.hint'),
        target: { commands: [item.path] },
      }),
    );
    const screens = visibleSettings((flag) => this.flags.isOn(flag), held).flatMap((group) =>
      group.items.map((item): Destination => ({
        id: `go:settings:${item.path}`,
        label: this.i18n.t(item.label),
        hint: `${this.i18n.t('shell.nav.settings')} → ${this.i18n.t(group.label)}`,
        target: {
          commands: item.path.startsWith('/') ? [item.path] : ['/settings', item.path],
        },
      })),
    );
    return [...sections, ...screens];
  });

  protected readonly items = computed<readonly PaletteItem[]>(() => {
    const found = findSettings({
      locale: this.i18n.locale(),
      query: this.query(),
      keys: this.keys(),
      placements: this.placements(),
      scope: this.scope(),
      breadcrumbOf: (route) => this.breadcrumbOf(route),
    });
    return paletteItems(
      this.query(),
      this.destinations(),
      found,
      (query) => this.text('order.label', { query }),
      this.text('order.hint'),
    );
  });

  protected readonly activeId = computed(() => this.items()[this.activeIndex()]?.id ?? null);

  ngOnInit(): void {
    // The Settings screens are named in the Settings and Staff catalogues, which the screen behind the
    // palette may not have asked for. They are fetched when the palette opens, not with the shell, so the
    // first paint is unchanged; the list redraws as they arrive (areas.mjs reads this call as the declaration).
    void this.i18n.require(['settings', 'staff']);
    void this.loadSettings();
  }

  protected text(
    key: Parameters<typeof paletteText.text>[1],
    values?: Readonly<Record<string, string | number>>,
  ): string {
    return paletteText.text(this.i18n.locale(), key, values);
  }

  protected groupLabel(group: PaletteItem['group']): string {
    return this.text(`group.${group}`);
  }

  protected onInput(value: string): void {
    this.query.set(value);
    this.activeIndex.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const count = this.items().length;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (count > 0) {
        const step = event.key === 'ArrowDown' ? 1 : -1;
        this.activeIndex.set((this.activeIndex() + step + count) % count);
      }
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const item = this.items()[this.activeIndex()];
      if (item !== undefined) {
        this.choose(item);
      }
    }
  }

  protected choose(item: PaletteItem): void {
    const { commands, queryParams } = item.target;
    this.closed.emit();
    if (queryParams === undefined) {
      void this.router.navigate([...commands]);
    } else {
      void this.router.navigate([...commands], { queryParams });
    }
  }

  protected dismiss(): void {
    this.closed.emit();
  }

  private breadcrumbOf(route: string): string {
    for (const group of SETTINGS_NAVIGATION) {
      const item = group.items.find((candidate) => candidate.path === route);
      if (item !== undefined) {
        return `${this.i18n.t(group.label)} → ${this.i18n.t(item.label)}`;
      }
    }
    return route;
  }

  private async loadSettings(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.capabilities.has('TENANT_CONFIGURATION_READ')) {
      return;
    }
    const [keys, placements] = await Promise.allSettled([
      this.configurationApi.keys(tenantId),
      this.configurationApi.placements(tenantId),
    ]);
    if (keys.status === 'fulfilled') {
      this.keys.set(keys.value);
    }
    if (placements.status === 'fulfilled') {
      this.placements.set(placements.value);
    }
  }
}
