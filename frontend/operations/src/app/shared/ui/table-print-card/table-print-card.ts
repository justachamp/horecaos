import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { QQrCode } from '../qr-code';
import { QR_MAX_BYTE_CAPACITY } from '../qr-encode';

/**
 * A lowercase DNS name of at least two labels — the same shape
 * `tenant.channel_hostnames` enforces (V0403, `ck_channel_hostname_format`), so
 * a value the platform would have refused never becomes a printed address.
 */
const HOSTNAME = /^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/;

/**
 * The absolute address a table's printed QR code sends a phone to:
 * `https://<verified hostname>/dine-in/<token>` — the storefront's scan route
 * (`frontend/storefront`'s `DineInScanComponent`), which spends the token once
 * and replaces the URL with a token-free one.
 *
 * Null when there is nothing honest to build: no verified hostname (the tenant
 * has none, or `QrChannelSource.storefrontHostname` found two candidates and
 * refused to guess), no token, or a hostname that is not a DNS name. The card
 * then encodes the bare token and says so — a phone camera cannot open a bare
 * token, and pretending otherwise prints a card that looks finished and is not.
 */
export function tableQrUrl(
  hostname: string | null | undefined,
  token: string | null | undefined,
): string | null {
  const host = hostname?.trim().toLowerCase();
  if (!host || !token || !HOSTNAME.test(host)) {
    return null;
  }
  return `https://${host}/dine-in/${encodeURIComponent(token)}`;
}

/**
 * A table's printable QR card (rows `10.5b`/`X.36`, wave P38) — what a
 * manager hands to whoever is walking the room with a printer after
 * issuing or rotating a table's code.
 *
 * Renders the scan address through `q-qr-code` (`X.35`, wave P17), which landed
 * in this same integration — `qr-code.ts`'s own doc names this card as its
 * third call site. A guest's phone camera opens `https://<hostname>/dine-in/
 * <token>` on the tenant's verified storefront hostname ({@link tableQrUrl});
 * the token also still prints as text underneath, for the rare scan-fails case
 * `shared.tablePrintCard.tokenLabel` already existed for.
 *
 * **No verified hostname means a visible warning, not a silent fallback.** The
 * mark then encodes the bare token, which is all the card ever held before this
 * follow-up — a phone camera cannot open it — and the card says so, hidden
 * from the printed page (the warning is for the manager, not for a guest).
 *
 * **Never caches the token.** `qrToken` is only ever the plaintext
 * `FloorPlanController` just minted, held by the host component for exactly
 * as long as the rotation dialog stays open — this component does not
 * store it anywhere itself, matching `FloorPlanController`'s own doc that
 * there is no endpoint that will return a table's token a second time.
 */
@Component({
  selector: 'q-table-print-card',
  imports: [TPipe, QQrCode],
  templateUrl: './table-print-card.html',
  styleUrl: './table-print-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TablePrintCard {
  readonly branchName = input.required<string>();
  readonly tableCode = input.required<string>();
  readonly tableDisplayName = input.required<string>();
  /** The plaintext token, shown exactly once — null renders "no code issued yet". */
  readonly qrToken = input<string | null>(null);
  /**
   * The tenant's verified storefront hostname (`FloorPlanController
   * .SettingsResponse.storefrontHostname`), or null when none qualifies.
   */
  readonly storefrontHostname = input<string | null>(null);

  private readonly builtUrl = computed(() => tableQrUrl(this.storefrontHostname(), this.qrToken()));

  /**
   * `https://<hostname>/dine-in/<token>`, or null when the address cannot
   * honestly be built or does not fit the encoder's ceiling
   * (`QR_MAX_BYTE_CAPACITY`, 106 bytes: a 22-character token leaves room for a
   * hostname of about 67 characters, well past any platform subdomain and most
   * custom domains — but not all of them, and a `tooLong` box printed on a
   * card is worse than the honest fallback).
   */
  protected readonly scanUrl = computed(() => {
    const url = this.builtUrl();
    return url !== null && new TextEncoder().encode(url).length <= QR_MAX_BYTE_CAPACITY
      ? url
      : null;
  });

  /**
   * Why the mark holds the bare token, or null when it holds the address.
   * `NO_HOST`: nothing verified to point a phone at. `TOO_LONG`: there is a
   * hostname, but the whole address will not fit in this encoder's QR symbol.
   */
  protected readonly fallback = computed<'NO_HOST' | 'TOO_LONG' | null>(() => {
    if (this.scanUrl() !== null) {
      return null;
    }
    return this.builtUrl() === null ? 'NO_HOST' : 'TOO_LONG';
  });

  /** What the QR mark encodes: the scan address, else the bare token. */
  protected readonly markValue = computed(() => this.scanUrl() ?? this.qrToken() ?? '');

  /** The bare hostname shown under the mark, so a manager can see which site a scan opens. */
  protected readonly scanHost = computed(() => {
    const url = this.scanUrl();
    return url ? new URL(url).host : null;
  });
}
