/* Kitchen display.
 *
 * The only screen in this console that is not read at a desk. It is read across
 * a hot room from two metres, by someone whose hands are covered in flour, and
 * every decision here follows from that:
 *
 *   - The scale is coarser. `q-caption` does not appear on this screen at all;
 *     the smallest thing on it is `q-body`. The elapsed clock is `q-display`,
 *     because on a bad night that number is the only thing anyone looks at.
 *   - Targets are 56–64px. The imported `Button` tops out at 40px, which is a
 *     desk control, so this file has its own.
 *   - Nothing important is on hover. Every action is a permanently visible
 *     button; hover only changes a background.
 *   - Exactly one blue button per ticket, and it is always the next step. A
 *     cook glancing at a card should not have to read to find what to press.
 *
 * Lateness is the one place colour is allowed to fill a band. That is status,
 * not decoration, it is error/success only, and it is always paired with words
 * ("23 min over") so it survives a colour-blind cook and a sun-washed screen.
 */

import { useState } from "react";
import {
  KITCHEN_TICKETS, TODAY, NOW,
} from "./data";
import {
  ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
  TONE, dt, day, EmptyState,
} from "./components";

/* ── local primitives ──────────────────────────────────────────────────────
 * Both exist because the shared set is sized for a desk. Neither should be
 * promoted to components.jsx until a second wall-mounted screen needs them.
 */

/** A status chip at reading distance. Square, unlike StatusPill, because the
 *  0px rule only exempts the shared pill. Dot plus word, never colour alone. */
function Chip({ tone = "neutral", children, strong }) {
  const c = TONE[tone] || TONE.neutral;
  return (
    <span
      className={strong ? "q-emphasis" : "q-body"}
      style={{
        display: "inline-flex", alignItems: "center", gap: 8,
        padding: "6px 10px", background: c.tint, color: c.text,
        whiteSpace: "nowrap",
      }}
    >
      <span style={{ width: 10, height: 10, borderRadius: "50%", background: c.dot, flexShrink: 0 }} />
      {children}
    </span>
  );
}

/** A glove-sized button. 0px corners, hairline, no shadow. */
function BigButton({ variant = "neutral", height = 56, onClick, children, style }) {
  const variants = {
    primary: { background: blue, color: "#fff", border: "1px solid transparent" },
    neutral: { background: surface1, color: ink, border: `1px solid ${hairline}` },
    quiet:   { background: canvas, color: inkMuted, border: `1px solid ${hairline}` },
  };
  const v = variants[variant];
  return (
    <button
      type="button"
      onClick={onClick}
      className="q-body"
      style={{
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        height, padding: "0 16px", borderRadius: 0, cursor: "pointer",
        transition: "background var(--q-dur-base) var(--q-ease-productive)",
        whiteSpace: "nowrap", ...v, ...style,
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.background =
          variant === "primary" ? "var(--q-primary-hover)" : "var(--q-surface-2)";
      }}
      onMouseLeave={(e) => { e.currentTarget.style.background = v.background; }}
    >
      {children}
    </button>
  );
}

/* ── line and ticket state ─────────────────────────────────────────────────*/

const LINE_TONE = { WAITING: "neutral", COOKING: "pending", READY: "healthy" };
const LINE_LABEL = { WAITING: "Waiting", COOKING: "Cooking", READY: "Ready" };

/** Overrun drives sort order and the header band. An unfired ticket sorts to
 *  the bottom of the board with a finite sentinel, so two of them compare
 *  cleanly instead of producing NaN. */
const overrun = (t) => (t.firedAt ? t.elapsedMinutes - t.targetMinutes : -9999);

function timeState(t) {
  if (!t.firedAt) return "unfired";
  if (t.elapsedMinutes > t.targetMinutes) return "over";
  if (t.targetMinutes - t.elapsedMinutes <= 3) return "due";
  return "ok";
}

/* ── a line ────────────────────────────────────────────────────────────────*/

function LineRow({ line, onStart, onReady, onUndo, last }) {
  const action =
    line.state === "WAITING" ? { label: "Start", run: onStart, variant: "neutral" }
    : line.state === "COOKING" ? { label: "Ready", run: onReady, variant: "neutral" }
    : { label: "Undo", run: onUndo, variant: "quiet" };

  return (
    <div
      style={{
        display: "flex", alignItems: "center", gap: 12, padding: "10px 16px",
        borderBottom: last ? "none" : `1px solid ${hairline}`,
        background: line.state === "READY" ? surface1 : canvas,
      }}
    >
      <div
        className="q-subhead q-tnum"
        style={{ width: 48, flexShrink: 0, textAlign: "right", color: ink, fontWeight: 600 }}
      >
        {line.qty}×
      </div>
      <div
        className="q-subhead"
        style={{
          flex: 1, minWidth: 0, color: line.state === "READY" ? inkMuted : ink,
          textDecoration: line.state === "READY" ? "line-through" : "none",
        }}
      >
        {line.name}
      </div>
      <Chip tone={LINE_TONE[line.state]} strong>{LINE_LABEL[line.state]}</Chip>
      <BigButton variant={action.variant} onClick={action.run} style={{ width: 116, flexShrink: 0 }}>
        {action.label}
      </BigButton>
    </div>
  );
}

/* ── a ticket ──────────────────────────────────────────────────────────────*/

function TicketCard({ ticket, onLine, onStartAll, onAllReady }) {
  const ts = timeState(ticket);
  const over = ts === "over";
  const allReady = ticket.lines.every((l) => l.state === "READY");
  const anyWaiting = ticket.lines.some((l) => l.state === "WAITING");
  const readyCount = ticket.lines.filter((l) => l.state === "READY").length;

  /* The band is the only fill on this screen, and only these two tones use it. */
  const band =
    over ? { bg: "var(--q-error-tint)", fg: "var(--q-error-text)" }
    : allReady ? { bg: "var(--q-success-tint)", fg: "var(--q-success-text)" }
    : { bg: canvas, fg: ink };

  const barTrack = "var(--q-surface-2)";
  const pct = ticket.targetMinutes
    ? Math.min(ticket.elapsedMinutes / ticket.targetMinutes, 1) * 100
    : 0;

  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
      {/* header — order number, station, and the verdict */}
      <div
        style={{
          display: "flex", alignItems: "center", gap: 12, padding: "12px 16px",
          background: band.bg, borderBottom: `1px solid ${hairline}`,
        }}
      >
        <span className="q-headline q-tnum" style={{ color: band.fg, fontWeight: 600 }}>
          {ticket.shortId}
        </span>
        <span className="q-body" style={{ color: over ? band.fg : inkMuted }}>
          {ticket.station}
        </span>
        <span style={{ marginLeft: "auto" }}>
          {over ? (
            <Chip tone="failed" strong>{ticket.elapsedMinutes - ticket.targetMinutes} min over</Chip>
          ) : allReady ? (
            <Chip tone="healthy" strong>All ready</Chip>
          ) : ts === "due" ? (
            <Chip tone="pending" strong>{ticket.targetMinutes - ticket.elapsedMinutes} min left</Chip>
          ) : ts === "unfired" ? (
            <Chip tone="neutral" strong>Not started</Chip>
          ) : (
            <Chip tone="healthy" strong>On time</Chip>
          )}
        </span>
      </div>

      {/* clock — the number a cook reads from the pass */}
      <div style={{ padding: "12px 16px", borderBottom: `1px solid ${hairline}` }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
          <span
            className="q-display q-tnum"
            style={{ color: over ? "var(--q-error-text)" : ink, fontWeight: 600 }}
          >
            {ticket.firedAt ? ticket.elapsedMinutes : "—"}
          </span>
          <span className="q-body" style={{ color: inkMuted }}>
            min of {ticket.targetMinutes}
          </span>
          <span className="q-body q-tnum" style={{ marginLeft: "auto", color: inkMuted }}>
            {ticket.firedAt ? `Fired ${dt(ticket.firedAt)}` : "Not fired"}
          </span>
        </div>
        <div style={{ height: 12, background: barTrack, marginTop: 10 }}>
          <div
            style={{
              width: `${ticket.firedAt ? pct : 0}%`, height: "100%",
              background: over ? "var(--q-error)" : ink,
            }}
          />
        </div>
      </div>

      {/* lines — each carries its own state, which is the whole point */}
      <div>
        {ticket.lines.map((l, i) => (
          <LineRow
            key={`${ticket.orderId}-${i}`}
            line={l}
            last={i === ticket.lines.length - 1}
            onStart={() => onLine(i, "COOKING")}
            onReady={() => onLine(i, "READY")}
            onUndo={() => onLine(i, "COOKING")}
          />
        ))}
      </div>

      {/* ticket actions */}
      <div
        style={{
          display: "flex", gap: 0, padding: 12, borderTop: `1px solid ${hairline}`,
          background: surface1, alignItems: "center",
        }}
      >
        <span className="q-body q-tnum" style={{ color: inkMuted, marginRight: "auto" }}>
          {readyCount} of {ticket.lines.length} ready
        </span>
        {anyWaiting ? (
          <BigButton
            variant={ts === "unfired" ? "primary" : "neutral"}
            height={64}
            onClick={onStartAll}
            style={{ minWidth: 148 }}
          >
            Start all
          </BigButton>
        ) : null}
        <BigButton
          variant={ts === "unfired" ? "neutral" : "primary"}
          height={64}
          onClick={onAllReady}
          style={{ minWidth: 172, marginLeft: 12 }}
        >
          Ticket ready
        </BigButton>
      </div>
    </div>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function Kitchen({ station, setStation }) {
  const [tickets, setTickets] = useState(() =>
    KITCHEN_TICKETS.map((t) => ({ ...t, lines: t.lines.map((l) => ({ ...l })), handedOff: false })),
  );

  const patch = (orderId, fn) =>
    setTickets((ts) => ts.map((t) => (t.orderId === orderId ? fn(t) : t)));

  const setLine = (orderId, index, state) =>
    patch(orderId, (t) => ({
      ...t,
      firedAt: t.firedAt || (state === "COOKING" ? NOW : t.firedAt),
      lines: t.lines.map((l, i) => (i === index ? { ...l, state } : l)),
    }));

  const startAll = (orderId) =>
    patch(orderId, (t) => ({
      ...t,
      firedAt: t.firedAt || NOW,
      lines: t.lines.map((l) => (l.state === "WAITING" ? { ...l, state: "COOKING" } : l)),
    }));

  const allReady = (orderId) =>
    patch(orderId, (t) => ({
      ...t,
      firedAt: t.firedAt || NOW,
      lines: t.lines.map((l) => ({ ...l, state: "READY" })),
      handedOff: true,
    }));

  const undoHandOff = (orderId) => patch(orderId, (t) => ({ ...t, handedOff: false }));

  const stations = ["all", ...Array.from(new Set(KITCHEN_TICKETS.map((t) => t.station)))];
  const active = tickets.filter((t) => !t.handedOff);
  const passed = tickets.filter((t) => t.handedOff);

  const shown = active
    .filter((t) => station === "all" || t.station === station)
    .sort((a, b) => overrun(b) - overrun(a));

  const lateOnBoard = active.filter((t) => timeState(t) === "over").length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      {/* ── board header ─────────────────────────────────────────────────
          Station filter left, service context right. Both sized to be read
          from the pass, not from a chair. */}
      <div
        style={{
          display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap",
          padding: 12, background: canvas, border: `1px solid ${hairline}`,
        }}
      >
        <div style={{ display: "flex", gap: 0 }}>
          {stations.map((s) => {
            const on = s === station;
            const count = s === "all" ? active.length : active.filter((t) => t.station === s).length;
            return (
              <button
                key={s}
                type="button"
                onClick={() => setStation(s)}
                className="q-subhead"
                style={{
                  display: "inline-flex", alignItems: "center", gap: 10,
                  height: 56, padding: "0 24px", borderRadius: 0, cursor: "pointer",
                  border: `1px solid ${on ? ink : hairline}`,
                  marginLeft: -1,
                  background: on ? ink : canvas,
                  color: on ? "#fff" : ink,
                  position: "relative", zIndex: on ? 1 : 0,
                }}
              >
                {s === "all" ? "All stations" : s}
                <span
                  className="q-body q-tnum"
                  style={{ color: on ? "var(--q-inverse-ink-muted)" : inkSubtle }}
                >
                  {count}
                </span>
              </button>
            );
          })}
        </div>

        <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: 24 }}>
          {lateOnBoard ? <Chip tone="failed" strong>{lateOnBoard} late on the board</Chip> : null}
          <span className="q-body q-tnum" style={{ color: inkMuted }}>
            Avg prep today {TODAY.averagePrepMinutes} min
          </span>
          <span className="q-body q-tnum" style={{ color: inkMuted }}>
            {TODAY.ordersOpen} open · {day(NOW)}
          </span>
        </div>
      </div>

      {/* ── the board ────────────────────────────────────────────────────
          Columns, ordered by how far past target each ticket is. The card a
          cook must deal with first is always top-left. */}
      {shown.length ? (
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(auto-fill, minmax(440px, 1fr))",
            gap: 16, alignItems: "start",
          }}
        >
          {shown.map((t) => (
            <TicketCard
              key={t.orderId}
              ticket={t}
              onLine={(i, state) => setLine(t.orderId, i, state)}
              onStartAll={() => startAll(t.orderId)}
              onAllReady={() => allReady(t.orderId)}
            />
          ))}
        </div>
      ) : (
        <EmptyState
          title={station === "all" ? "Nothing on the board" : `Nothing on ${station}`}
          description="Every fired ticket has been handed to the pass."
        />
      )}

      {/* ── the pass ─────────────────────────────────────────────────────
          Handed-off tickets leave the board but stay visible and reversible,
          because a ticket marked ready by a mistap must come back in one
          press, not by finding a manager. */}
      {passed.length ? (
        <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
          <div
            className="q-subhead"
            style={{ padding: "12px 16px", borderBottom: `1px solid ${hairline}`, color: ink }}
          >
            On the pass
            <span className="q-body q-tnum" style={{ color: inkSubtle, marginLeft: 12 }}>
              {passed.length}
            </span>
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 12, padding: 12 }}>
            {passed.map((t) => (
              <div
                key={t.orderId}
                style={{
                  display: "flex", alignItems: "center", gap: 16,
                  padding: "8px 8px 8px 16px", border: `1px solid ${hairline}`,
                  background: "var(--q-success-tint)",
                }}
              >
                <span className="q-headline q-tnum" style={{ color: "var(--q-success-text)", fontWeight: 600 }}>
                  {t.shortId}
                </span>
                <span className="q-body" style={{ color: "var(--q-success-text)" }}>{t.station}</span>
                <BigButton variant="quiet" height={48} onClick={() => undoHandOff(t.orderId)}>
                  Undo
                </BigButton>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
