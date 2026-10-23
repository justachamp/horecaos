import { LocationScope } from './operations-paths';

/**
 * Where the terminals' and the kiosks' endpoints live (ADR 0155, ADR 0162).
 *
 * Three groups, kept apart because three different callers use them: **the terminal's own** (`terminal-session`,
 * called by an enrolled tablet with its device bearer, from `device/`), **the console's** (the Staff → Терминалы
 * registry and Policy card, «Мой PIN», the person card's PIN panel) and **the kiosk's** (pairing, registry and
 * configuration under a branch). Pure string builders, no `ApiClient`: the device shell's plain `fetch` and the
 * console's `ApiClient` both build their URLs from here, so the two cannot disagree about where an endpoint lives.
 */
const TENANTS = '/api/v1/tenants';
const OPERATIONS = '/api/v1/operations/tenants';

function branch(scope: LocationScope): string {
  return `${TENANTS}/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}`;
}

export const terminalPaths = {
  // ---- the terminal's own (ADR 0155): a device bearer, never a staff session ----

  /** `GET`: who may unlock this terminal, as labels. `Cache-Control: no-store`. */
  roster(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/roster`;
  },
  /** `POST { memberId, pin }`: opens the device's one session. The token comes back once. */
  unlocks(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/unlocks`;
  },
  /** `POST { memberId, activationCode, newPin }`: sets a PIN with a single-use code, and opens a session. */
  pinActivations(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/pin-activations`;
  },
  /** `POST { currentPin, newPin }` with the live session. */
  pinChanges(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/pin-changes`;
  },
  /** `POST`: a tap that is not itself a mutating call keeps the terminal unlocked. 204. */
  touch(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/touch`;
  },
  /** `POST`: ends this device's session. 204. */
  locks(scope: LocationScope): string {
    return `${branch(scope)}/terminal-session/locks`;
  },

  // ---- the console's (ADR 0155) ----

  /** `GET`: every device at the branch, of any class. */
  terminals(scope: LocationScope): string {
    return `${branch(scope)}/terminals`;
  },
  terminal(scope: LocationScope, deviceId: string): string {
    return `${this.terminals(scope)}/${enc(deviceId)}`;
  },
  terminalRevoke(scope: LocationScope, deviceId: string): string {
    return `${this.terminal(scope, deviceId)}/revoke`;
  },
  /** `GET ?cursor=`: who was unlocked at the device, when, and how it ended. */
  terminalSessions(scope: LocationScope, deviceId: string): string {
    return `${this.terminal(scope, deviceId)}/sessions`;
  },
  /** `GET`/`PUT`: the tenant's five policy values, held at TENANT scope. */
  policy(tenantId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/terminal-policy`;
  },
  /** «Мой PIN»: `GET` the state and my last twenty unlocks. No member id: the server resolves the row from the token. */
  myPin(tenantId: string): string {
    return `${OPERATIONS}/${enc(tenantId)}/staff/me/pin`;
  },
  /** `POST`: my own activation code, returned once. */
  myPinActivations(tenantId: string): string {
    return `${this.myPin(tenantId)}-activations`;
  },
  /** A person's PIN as the person card shows it (`GET`), at the tenant or at one branch. */
  memberPin(tenantId: string, memberId: string, scope?: LocationScope): string {
    return `${memberBase(tenantId, memberId, scope)}/pin`;
  },
  /** `POST { reason }`: a manager issues an activation code, shown once. */
  memberPinActivations(tenantId: string, memberId: string, scope?: LocationScope): string {
    return `${memberBase(tenantId, memberId, scope)}/pin-activations`;
  },
  /** `POST { reason }`: clears the credential and ends the person's sessions. */
  memberPinReset(tenantId: string, memberId: string, scope?: LocationScope): string {
    return `${memberBase(tenantId, memberId, scope)}/pin-reset`;
  },

  // ---- the kiosk's (ADR 0162) ----

  /** `GET`: what a pending code claims (class, label, expiry). Rate limited per approver. */
  kioskEnrolment(scope: LocationScope, userCode: string): string {
    return `${branch(scope)}/kiosk/enrolments/${enc(userCode)}`;
  },
  /** `POST { displayName, channelId }`: pairs the kiosk showing the code. */
  kioskApprove(scope: LocationScope, userCode: string): string {
    return `${this.kioskEnrolment(scope, userCode)}/approve`;
  },
  /** `GET`: the KIOSK channels a kiosk may be paired to at this branch. */
  kioskChannels(scope: LocationScope): string {
    return `${branch(scope)}/kiosk/channels`;
  },
  /** `GET`: the branch's kiosks, active and revoked. */
  kioskDevices(scope: LocationScope): string {
    return `${branch(scope)}/kiosk/devices`;
  },
  /** `PUT { displayName, channelId }` with `If-Match`. */
  kioskDevice(scope: LocationScope, deviceId: string): string {
    return `${this.kioskDevices(scope)}/${enc(deviceId)}`;
  },
  kioskRevoke(scope: LocationScope, deviceId: string): string {
    return `${this.kioskDevice(scope, deviceId)}/revoke`;
  },
  /** `GET`, by the kiosk itself: its channel, branch, modes and payment methods, and its idle pictures (ADR 0187). */
  kioskConfiguration(scope: LocationScope): string {
    return `${branch(scope)}/kiosk/configuration`;
  },

  // ---- a KIOSK channel's idle media (ADR 0187) ----

  /** `GET` the list (an `ETag` is its version); `POST { assetId }` attaches, with `If-Match`. */
  kioskIdleMedia(scope: LocationScope, channelId: string): string {
    return `${branch(scope)}/kiosk/channels/${enc(channelId)}/idle-media`;
  },
  /** `POST { contentType, sizeBytes, filename }`: a constrained upload URL for a 9:16 picture of at most 1 MB. */
  kioskIdleMediaUploadRequest(scope: LocationScope, channelId: string): string {
    return `${this.kioskIdleMedia(scope, channelId)}/upload-requests`;
  },
  /** `GET`: where an upload's verification stands (`UPLOADED`, `AVAILABLE`, `REJECTED` and why). */
  kioskIdleMediaUpload(scope: LocationScope, channelId: string, assetId: string): string {
    return `${this.kioskIdleMedia(scope, channelId)}/uploads/${enc(assetId)}`;
  },
  kioskIdleMediaFinalize(scope: LocationScope, channelId: string, assetId: string): string {
    return `${this.kioskIdleMediaUpload(scope, channelId, assetId)}/finalize`;
  },
  /** `PUT { itemIds }` with `If-Match`: the whole list in its new order. */
  kioskIdleMediaOrder(scope: LocationScope, channelId: string): string {
    return `${this.kioskIdleMedia(scope, channelId)}/order`;
  },
  /** `DELETE` with `If-Match`. */
  kioskIdleMediaItem(scope: LocationScope, channelId: string, itemId: string): string {
    return `${this.kioskIdleMedia(scope, channelId)}/${enc(itemId)}`;
  },
} as const;

function memberBase(tenantId: string, memberId: string, scope?: LocationScope): string {
  const root =
    scope === undefined
      ? `${OPERATIONS}/${enc(tenantId)}`
      : `${OPERATIONS}/${enc(tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}`;
  return `${root}/staff/members/${enc(memberId)}`;
}

function enc(value: string): string {
  return encodeURIComponent(value);
}
