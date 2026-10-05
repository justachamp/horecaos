/**
 * What every operational screen may show about the accelerator, not about
 * correctness — every surface this feeds also has the 10s poll that must
 * work regardless (ADR 0045; `X.34`).
 */
export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'unavailable';

interface FrameBase {
  readonly channel: string;
  readonly scope: string;
  readonly occurredAt: string;
}

/** "Something in your scope changed, re-read it" — never carries the resource itself. */
export interface RealtimeSignalFrame extends FrameBase {
  readonly kind: 'signal';
  readonly resourceType: string;
  readonly resourceId: string | null;
  readonly version: number | null;
}

/** A registered exception (`COUNTERS`, `COURIER_POSITIONS`): a bounded payload inline. */
export interface RealtimeSnapshotFrame extends FrameBase {
  readonly kind: 'snapshot';
  readonly snapshot: unknown;
}

/**
 * "Reconnect and re-read your whole scope" — no replay buffer exists, so this
 * is what a `Last-Event-Id` resync answers with instead of stale frames.
 */
export interface RealtimeResyncFrame {
  readonly kind: 'resync';
}

export type RealtimeFrame = RealtimeSignalFrame | RealtimeSnapshotFrame | RealtimeResyncFrame;
