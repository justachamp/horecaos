/* Shared console primitives.
 *
 * Same role as Togora's components.jsx — a small contract every section file
 * imports — but rendered in the Qoida `.console` language rather than Togora's.
 * The differences are deliberate, not stylistic drift:
 *
 *   Togora                        Qoida console
 *   ------------------------------------------------------------------
 *   DM Sans                       IBM Plex Sans
 *   14px rounded cards            0px corners
 *   soft drop shadows             1px hairline, no shadow
 *   emoji as iconography          no emoji, ever
 *   dark navy #1A1A2E rail        near-black #161616 inverse surface
 *   colour used decoratively      platform blue used scarcely
 *
 * Sizes come from the closed type scale in tokens.css. Nothing here sets a
 * font-size; it sets a class.
 */

export const ink = "var(--q-ink)";
export const inkMuted = "var(--q-ink-muted)";
export const inkSubtle = "var(--q-ink-subtle)";
export const hairline = "var(--q-hairline)";
export const canvas = "var(--q-canvas)";
export const surface1 = "var(--q-surface-1)";
export const blue = "var(--q-primary)";

/* ── formatting ────────────────────────────────────────────────────────────
 * UZS is whole som, thousands-separated with spaces, no decimals, tabular.
 * ADR 0018 stores minor units as whole som, so there is nothing to divide.
 */
export const uzs = (minor) =>
  `${String(minor).replace(/\B(?=(\d{3})+(?!\d))/g, " ")} so'm`;

/** 24h clock, DD.MM order — the format ru and uz actually read. */
export const dt = (iso) => {
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

export const day = (iso) => {
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()}`;
};

/* ── status ────────────────────────────────────────────────────────────────
 * Tone is carried by a dot plus text, never by an icon fill and never by colour
 * alone — a status a colour-blind operator cannot read is not a status.
 * Yellow is a dot only; its text pair is the darker warning ink.
 */
export const TONE = {
  active:    { tint: "var(--q-success-tint)", text: "var(--q-success-text)", dot: "var(--q-success)" },
  healthy:   { tint: "var(--q-success-tint)", text: "var(--q-success-text)", dot: "var(--q-success)" },
  pending:   { tint: "var(--q-warning-tint)", text: "var(--q-warning-text)", dot: "var(--q-warning)" },
  degraded:  { tint: "var(--q-warning-tint)", text: "var(--q-warning-text)", dot: "var(--q-warning)" },
  failed:    { tint: "var(--q-error-tint)",   text: "var(--q-error-text)",   dot: "var(--q-error)" },
  suspended: { tint: "var(--q-error-tint)",   text: "var(--q-error-text)",   dot: "var(--q-error)" },
  info:      { tint: "var(--q-info-tint)",    text: "var(--q-info-text)",    dot: "var(--q-primary)" },
  neutral:   { tint: "var(--q-surface-1)",    text: "var(--q-ink-muted)",    dot: "var(--q-ink-subtle)" },
};

export function StatusPill({ tone = "neutral", children }) {
  const c = TONE[tone] || TONE.neutral;
  return (
    <span
      className="q-caption"
      style={{
        display: "inline-flex", alignItems: "center", gap: 6,
        padding: "2px 8px", background: c.tint, color: c.text,
        borderRadius: 9999, whiteSpace: "nowrap",
      }}
    >
      <span style={{ width: 6, height: 6, borderRadius: "50%", background: c.dot, flexShrink: 0 }} />
      {children}
    </span>
  );
}

/* ── surfaces ──────────────────────────────────────────────────────────────*/

export function Card({ children, style, padded = true }) {
  return (
    <div
      style={{
        background: canvas,
        border: `1px solid ${hairline}`,
        padding: padded ? 24 : 0,
        ...style,
      }}
    >
      {children}
    </div>
  );
}

export function SectionHeader({ title, description, right }) {
  return (
    <div style={{ display: "flex", alignItems: "flex-start", gap: 16, marginBottom: 24 }}>
      <div style={{ minWidth: 0 }}>
        <h1 className="q-title" style={{ margin: 0, color: ink }}>{title}</h1>
        {description ? (
          <p className="q-body-sm" style={{ margin: "4px 0 0", color: inkMuted, maxWidth: 640 }}>
            {description}
          </p>
        ) : null}
      </div>
      {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
    </div>
  );
}

/* ── actions ───────────────────────────────────────────────────────────────*/

const BTN_HEIGHT = { sm: 32, md: 40 };

export function Button({ variant = "primary", size = "md", onClick, children, disabled, style }) {
  const base = {
    display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 8,
    height: BTN_HEIGHT[size], padding: size === "sm" ? "0 12px" : "0 16px",
    border: "1px solid transparent", borderRadius: "var(--q-radius)",
    fontSize: 14, fontWeight: 400, letterSpacing: "0.16px",
    cursor: disabled ? "not-allowed" : "pointer", opacity: disabled ? 0.4 : 1,
    transition: "background var(--q-dur-base) var(--q-ease-productive)",
    whiteSpace: "nowrap",
  };
  const variants = {
    primary:   { background: blue, color: "#fff" },
    secondary: { background: ink, color: "#fff" },
    tertiary:  { background: "transparent", color: blue, borderColor: blue },
    ghost:     { background: "transparent", color: blue },
    danger:    { background: "var(--q-error)", color: "#fff" },
  };
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      style={{ ...base, ...variants[variant], ...style }}
    >
      {children}
    </button>
  );
}

/* ── data ──────────────────────────────────────────────────────────────────
 * 40px rows, surface-1 header, hover highlight, no zebra. Numeric columns are
 * right-aligned and tabular so digits line up down the column, which is the
 * whole reason an operator can scan a money column at all.
 */
export function DataTable({ columns, rows, onRowClick, selectedId, empty }) {
  if (!rows.length) return empty || <EmptyState title="Nothing here yet" />;

  return (
    <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
      <table style={{ width: "100%", borderCollapse: "collapse" }}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                className="q-caption"
                style={{
                  textAlign: c.align || "left", padding: "10px 16px",
                  background: surface1, color: inkMuted, fontWeight: 600,
                  borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap",
                }}
              >
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const selected = selectedId && row.id === selectedId;
            return (
              <tr
                key={row.id}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                style={{
                  cursor: onRowClick ? "pointer" : "default",
                  background: selected ? "var(--q-info-tint)" : canvas,
                  borderBottom: `1px solid ${hairline}`,
                }}
                onMouseEnter={(e) => {
                  if (!selected) e.currentTarget.style.background = surface1;
                }}
                onMouseLeave={(e) => {
                  if (!selected) e.currentTarget.style.background = canvas;
                }}
              >
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={c.align === "right" ? "q-body-sm q-tnum" : "q-body-sm"}
                    style={{
                      padding: "10px 16px", textAlign: c.align || "left",
                      color: ink, height: 40, verticalAlign: "middle",
                    }}
                  >
                    {c.render ? c.render(row[c.key], row) : row[c.key]}
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ── navigation ────────────────────────────────────────────────────────────*/

export function Tabs({ tabs, active, onChange }) {
  return (
    <div style={{ display: "flex", borderBottom: `1px solid ${hairline}`, marginBottom: 24, gap: 0 }}>
      {tabs.map((t) => {
        const on = t.id === active;
        return (
          <button
            key={t.id}
            type="button"
            onClick={() => onChange(t.id)}
            className="q-body-sm"
            style={{
              padding: "12px 16px", background: "transparent", border: "none",
              borderBottom: on ? `2px solid ${blue}` : "2px solid transparent",
              color: on ? ink : inkMuted, cursor: "pointer", marginBottom: -1,
              transition: "color var(--q-dur-base) var(--q-ease-productive)",
            }}
          >
            {t.label}
            {t.count !== undefined ? (
              <span className="q-tnum" style={{ color: inkSubtle, marginLeft: 8 }}>{t.count}</span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

/* A filter bar. Every list screen in the inventory has one, so it is a
 * component rather than a per-screen arrangement. */
export function FilterBar({ children }) {
  return (
    <div
      style={{
        display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap",
        padding: 12, background: canvas, border: `1px solid ${hairline}`,
        borderBottom: "none",
      }}
    >
      {children}
    </div>
  );
}

export function Select({ value, onChange, options, label }) {
  return (
    <label style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      {label ? <span className="q-caption" style={{ color: inkMuted }}>{label}</span> : null}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="q-body-sm"
        style={{
          height: 32, padding: "0 8px", background: canvas, color: ink,
          border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)",
        }}
      >
        {options.map((o) => (
          <option key={o.value ?? o} value={o.value ?? o}>{o.label ?? o}</option>
        ))}
      </select>
    </label>
  );
}

export function SearchInput({ value, onChange, placeholder }) {
  return (
    <input
      value={value}
      onChange={(e) => onChange(e.target.value)}
      placeholder={placeholder}
      className="q-body-sm"
      style={{
        height: 32, minWidth: 240, padding: "0 8px",
        background: canvas, color: ink,
        border: "none", borderBottom: `1px solid ${hairline}`,
        outline: "none",
      }}
      onFocus={(e) => { e.target.style.borderBottom = `2px solid ${blue}`; }}
      onBlur={(e) => { e.target.style.borderBottom = `1px solid ${hairline}`; }}
    />
  );
}

/* ── read-only display ─────────────────────────────────────────────────────*/

export function Field({ label, value, mono }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>{label}</div>
      <div
        className="q-body-sm"
        style={{ color: ink, fontFamily: mono ? "var(--q-font-mono)" : undefined, wordBreak: "break-word" }}
      >
        {value ?? "—"}
      </div>
    </div>
  );
}

export function FieldGrid({ fields, columns = 3 }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`, gap: 24 }}>
      {fields.map((f) => <Field key={f.label} {...f} />)}
    </div>
  );
}

export function KpiTile({ label, value, meta }) {
  return (
    <Card style={{ padding: 16 }}>
      <div className="q-caption" style={{ color: inkMuted }}>{label}</div>
      <div className="q-data-lg" style={{ color: ink, marginTop: 4 }}>{value}</div>
      {meta ? <div className="q-caption" style={{ color: inkSubtle, marginTop: 2 }}>{meta}</div> : null}
    </Card>
  );
}

/* ── states ────────────────────────────────────────────────────────────────
 * Three of them, deliberately distinct. An empty list, a plan lock, and a
 * capability denial look the same to a naive implementation and mean entirely
 * different things: nothing here yet, buy this, or you may not.
 */
export function EmptyState({ title, description, action }) {
  return (
    <div style={{ padding: 48, textAlign: "center", background: canvas, border: `1px solid ${hairline}` }}>
      <div className="q-body" style={{ color: ink }}>{title}</div>
      {description ? (
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>{description}</div>
      ) : null}
      {action ? <div style={{ marginTop: 16 }}>{action}</div> : null}
    </div>
  );
}

export function DeniedState({ capability }) {
  return (
    <div style={{ padding: 48, textAlign: "center", background: canvas, border: `1px solid ${hairline}` }}>
      <div className="q-body" style={{ color: ink }}>You do not have access to this</div>
      <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
        It needs the <code style={{ fontFamily: "var(--q-font-mono)" }}>{capability}</code> capability.
        Ask a platform administrator.
      </div>
    </div>
  );
}

/* ── overlays ──────────────────────────────────────────────────────────────*/

export function Drawer({ title, onClose, children, width = 560 }) {
  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)",
        display: "flex", justifyContent: "flex-end", zIndex: 50,
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width, maxWidth: "100%", height: "100%", background: canvas,
          borderLeft: `1px solid ${hairline}`, display: "flex", flexDirection: "column",
        }}
      >
        <div
          style={{
            display: "flex", alignItems: "center", gap: 16, padding: "16px 24px",
            borderBottom: `1px solid ${hairline}`, flexShrink: 0,
          }}
        >
          <div className="q-subhead" style={{ color: ink, minWidth: 0 }}>{title}</div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="q-body"
            style={{
              marginLeft: "auto", background: "transparent", border: "none",
              color: inkMuted, cursor: "pointer", padding: 4, lineHeight: 1,
            }}
          >
            ✕
          </button>
        </div>
        <div style={{ padding: 24, overflowY: "auto", flex: 1 }}>{children}</div>
      </div>
    </div>
  );
}

/* A per-status timeline. The inventory calls this out as an operator-facing
 * surface rather than audit evidence, so it belongs in the component set. */
export function Timeline({ entries }) {
  return (
    <div style={{ display: "flex", flexDirection: "column" }}>
      {entries.map((e, i) => (
        <div key={i} style={{ display: "flex", gap: 12 }}>
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", flexShrink: 0 }}>
            <span
              style={{
                width: 8, height: 8, borderRadius: "50%", marginTop: 6,
                background: e.tone ? TONE[e.tone].dot : "var(--q-ink-subtle)",
              }}
            />
            {i < entries.length - 1 ? (
              <span style={{ width: 1, flex: 1, background: hairline, minHeight: 24 }} />
            ) : null}
          </div>
          <div style={{ paddingBottom: 16, minWidth: 0 }}>
            <div className="q-body-sm" style={{ color: ink }}>{e.label}</div>
            <div className="q-caption" style={{ color: inkSubtle }}>
              {e.at}{e.actor ? ` · ${e.actor}` : ""}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
