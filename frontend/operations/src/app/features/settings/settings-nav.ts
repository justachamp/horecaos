import { MessageKey } from '../../core/i18n/messages.en';

/**
 * The Settings rail, grouped per `docs/operations-spec/settings.md` §Navigation
 * — nouns a restaurant manager uses, not an alphabetical list of screen names.
 * Only P-tier screens (10.1–10.4, 10.6–10.10) plus the moved 10.8 appear;
 * 10.5's own row exists because the spec's nav table lists it even though the
 * screen itself renders `q-not-built-page` today (see settings.routes.ts).
 */
export interface SettingsNavItem {
  readonly path: string;
  readonly label: MessageKey;
  /** The one-line purpose §10.0's index shows beside the label. */
  readonly description: MessageKey;
  /** Rendered by the not-built route when this screen has no component yet. */
  readonly builtRoute: boolean;
}

export interface SettingsNavGroup {
  readonly label: MessageKey;
  readonly items: readonly SettingsNavItem[];
}

export const SETTINGS_NAVIGATION: readonly SettingsNavGroup[] = [
  {
    label: 'settings.nav.group.business',
    items: [
      {
        path: 'brand',
        label: 'settings.nav.brandProfile',
        description: 'settings.home.description.brandProfile',
        builtRoute: true,
      },
      {
        path: 'locations',
        label: 'settings.nav.locations',
        description: 'settings.home.description.locations',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.selling',
    items: [
      {
        path: 'sales-channels',
        label: 'settings.nav.salesChannels',
        description: 'settings.home.description.salesChannels',
        builtRoute: true,
      },
      {
        path: 'channel-setup',
        label: 'settings.nav.channelSetup',
        description: 'settings.home.description.channelSetup',
        builtRoute: false,
      },
      {
        path: 'order-policy',
        label: 'settings.nav.orderPolicy',
        description: 'settings.home.description.orderPolicy',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.money',
    items: [
      {
        path: 'payment-methods',
        label: 'settings.nav.paymentMethods',
        description: 'settings.home.description.paymentMethods',
        builtRoute: false,
      },
      {
        path: 'fiscalization',
        label: 'settings.nav.fiscalization',
        description: 'settings.home.description.fiscalization',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.messages',
    items: [
      {
        path: 'notifications',
        label: 'settings.nav.notifications',
        description: 'settings.home.description.notifications',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.connections',
    items: [
      {
        path: 'integrations',
        label: 'settings.nav.integrations',
        description: 'settings.home.description.integrations',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.reference',
    items: [
      {
        path: 'reference-data',
        label: 'settings.nav.referenceData',
        description: 'settings.home.description.referenceData',
        builtRoute: true,
      },
    ],
  },
  {
    label: 'settings.nav.group.privacy',
    items: [
      {
        path: 'data-privacy',
        label: 'settings.nav.dataPrivacy',
        description: 'settings.home.description.dataPrivacy',
        builtRoute: true,
      },
    ],
  },
];

export const SETTINGS_NAV_ITEMS: readonly SettingsNavItem[] = SETTINGS_NAVIGATION.flatMap(
  (group) => group.items,
);
