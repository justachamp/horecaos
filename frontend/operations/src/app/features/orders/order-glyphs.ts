import { MessageKey } from '../../core/i18n/messages.en';
import { GlyphName, isGlyphName } from '../../shared/ui/glyph/glyph';

/**
 * Which mark the order board draws for a channel and for a payment method (gap map row `1.1`: «source
 * icon» and «payment icon»).
 *
 * Both are decided here, in code, from a closed vocabulary the platform owns: `SalesChannelSystemType`
 * (ADR 0036) and `PaymentMethod` (ADR 0013). A tenant's own channel carries a free-text `icon` name
 * (`SalesChannel.icon`, V0245); it can pick one of {@link GlyphName}'s marks and nothing else, so a name
 * the code does not know draws the system type's mark, and an unknown system type draws the generic one.
 * It is never markup.
 */

/** `SalesChannelSystemType`, the closed nine (ADR 0036), as the platform spells them. */
export const CHANNEL_SYSTEM_TYPES = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
] as const;
export type ChannelSystemType = (typeof CHANNEL_SYSTEM_TYPES)[number];

const SYSTEM_TYPE_GLYPH: Readonly<Record<ChannelSystemType, GlyphName>> = {
  WEB: 'web',
  IOS: 'mobile',
  ANDROID: 'mobile',
  TELEGRAM: 'telegram',
  KIOSK: 'kiosk',
  QR_TABLE: 'qr',
  CALL_CENTRE: 'headset',
  AGGREGATOR: 'courier',
  POS: 'register',
};

export const CHANNEL_SYSTEM_TYPE_LABEL_KEYS: Readonly<Record<ChannelSystemType, MessageKey>> = {
  WEB: 'orders.source.type.WEB',
  IOS: 'orders.source.type.IOS',
  ANDROID: 'orders.source.type.ANDROID',
  TELEGRAM: 'orders.source.type.TELEGRAM',
  KIOSK: 'orders.source.type.KIOSK',
  QR_TABLE: 'orders.source.type.QR_TABLE',
  CALL_CENTRE: 'orders.source.type.CALL_CENTRE',
  AGGREGATOR: 'orders.source.type.AGGREGATOR',
  POS: 'orders.source.type.POS',
};

function isChannelSystemType(value: string | null | undefined): value is ChannelSystemType {
  return (CHANNEL_SYSTEM_TYPES as readonly string[]).includes(value ?? '');
}

/** What the source column draws for an order: its channel's kind, or the channel's own pick among the marks. */
export interface SourceGlyph {
  readonly glyph: GlyphName;
  /** The closed-vocabulary word for the channel's kind, when the platform sent one this client knows. */
  readonly typeLabelKey: MessageKey | null;
}

export function sourceGlyph(order: {
  readonly channelSystemType?: string | null;
  readonly channelIcon?: string | null;
  readonly origin?: string | null;
}): SourceGlyph | null {
  const type = isChannelSystemType(order.channelSystemType) ? order.channelSystemType : null;
  // An order an aggregator pushed (`origin = MARKETPLACE`) is drawn as one even when the channel it was
  // filed under has since been removed from the registry.
  const aggregator = order.origin === 'MARKETPLACE';
  if (type === null && !aggregator && !order.channelSystemType) {
    return null;
  }
  const chosen = isGlyphName(order.channelIcon) ? order.channelIcon : null;
  const fromType = type ? SYSTEM_TYPE_GLYPH[type] : aggregator ? 'courier' : 'channel';
  return {
    glyph: chosen ?? fromType,
    typeLabelKey: type
      ? CHANNEL_SYSTEM_TYPE_LABEL_KEYS[type]
      : aggregator
        ? CHANNEL_SYSTEM_TYPE_LABEL_KEYS['AGGREGATOR']
        : null,
  };
}

/** `PaymentMethod`'s codes (ADR 0013), and the word for each. */
export const PAYMENT_METHOD_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  CASH: 'orders.paymentMethod.CASH',
  CLICK: 'orders.paymentMethod.CLICK',
  PAYME: 'orders.paymentMethod.PAYME',
  TELEGRAM: 'orders.paymentMethod.TELEGRAM',
  MARKETPLACE: 'orders.paymentMethod.MARKETPLACE',
};

const PAYMENT_METHOD_GLYPH: Readonly<Record<string, GlyphName>> = {
  CASH: 'cash',
  CLICK: 'card',
  PAYME: 'card',
  TELEGRAM: 'card',
  MARKETPLACE: 'courier',
};

/** The mark for a payment method code: a known one's own, the generic coin for a code the client has not met, nothing for no code. */
export function paymentMethodGlyph(code: string | null | undefined): GlyphName | null {
  if (!code) {
    return null;
  }
  return PAYMENT_METHOD_GLYPH[code] ?? 'coin';
}

/** `fiscal.fiscal_documents.status`'s ten values, and the word for each, for the board's «Ф» chip. */
export const FISCAL_STATUS_LABEL_KEYS: Readonly<Record<string, MessageKey>> = {
  PENDING: 'orders.fiscalStatus.PENDING',
  SUBMITTED: 'orders.fiscalStatus.SUBMITTED',
  ISSUED: 'orders.fiscalStatus.ISSUED',
  FAILED: 'orders.fiscalStatus.FAILED',
  BLOCKED: 'orders.fiscalStatus.BLOCKED',
  NOT_APPLICABLE: 'orders.fiscalStatus.NOT_APPLICABLE',
  CORRECTION_REQUESTED: 'orders.fiscalStatus.CORRECTION_REQUESTED',
  VOID_REQUESTED: 'orders.fiscalStatus.VOID_REQUESTED',
  CORRECTED: 'orders.fiscalStatus.CORRECTED',
  VOIDED: 'orders.fiscalStatus.VOIDED',
};
