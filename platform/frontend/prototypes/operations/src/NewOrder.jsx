/* Take an order.
 *
 * The screen the whole console exists for. It is used with a phone against one
 * ear, so it is designed around a single rule: the operator never navigates.
 * Everything — customer, items, modifiers, fulfilment, payment, note, total —
 * is on one surface, and the order can be finished from top to bottom without a
 * next button, a modal, or a scroll back up.
 *
 * The three shortcuts that actually save the seconds:
 *
 *   1. Popular items are one tap. Six dishes are most of the volume; typing
 *      their names is wasted time.
 *   2. Modifiers are never asked. Adding an item applies the required group's
 *      default, so the common order costs zero modifier taps; the basket line
 *      expands only when the caller wants something other than the default.
 *   3. Enter in the item search adds the top match, so a fast operator can take
 *      a whole basket without leaving the keyboard.
 *
 * The draft lives in App state and is only ever patched here, never replaced —
 * an operator who glances at the queue to answer "has 4819 gone out yet?" must
 * come back to the basket they left.
 */

import { useMemo, useRef, useState } from "react";
import { CATEGORIES, CUSTOMERS, MENU_ITEMS, MODIFIER_GROUPS, NOW } from "./data";
import {
  Button, Card, SectionHeader, StatusPill,
  uzs, dt, day,
  ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
} from "./components";

/* The fixtures charge a flat 15 000 on every DELIVERY order and 0 on PICKUP;
 * this mirrors them rather than inventing a second pricing rule. */
const DELIVERY_FEE_MINOR = 15_000;

/* Buffers on top of the kitchen's own prep estimate. Both are house numbers a
 * dispatcher would recognise: a ride across a Tashkent district, and the time a
 * collected order sits on the pass. */
const RIDE_MINUTES = 15;
const PASS_MINUTES = 5;

const BLANK = {
  lines: [], customer: null, address: null, newAddress: null,
  fulfilment: "DELIVERY", payment: "CASH", note: "", phone: "",
};

/* ── menu maths ────────────────────────────────────────────────────────────*/

const itemById = (id) => MENU_ITEMS.find((i) => i.id === id);
const groupsFor = (itemId) => MODIFIER_GROUPS.filter((g) => g.appliesTo.includes(itemId));
const categoryName = (id) => CATEGORIES.find((c) => c.id === id)?.name ?? "";

const defaultOptions = (itemId) =>
  groupsFor(itemId)
    .filter((g) => g.required)
    .map((g) => (g.options.find((o) => o.isDefault) || g.options[0]).id);

/* A basket line is identified by item plus its chosen options, so "osh, to'liq"
 * and "osh, katta" are two lines and tapping the same dish twice increments. */
const lineKey = (itemId, options) => `${itemId}|${[...options].sort().join(",")}`;

const chosenOptions = (itemId, options) =>
  groupsFor(itemId).flatMap((g) => g.options.filter((o) => options.includes(o.id)));

const unitOf = (itemId, options) =>
  itemById(itemId).priceMinor +
  chosenOptions(itemId, options).reduce((s, o) => s + o.priceDeltaMinor, 0);

const blankAddress = () => ({
  id: "new", label: "Uy", line: "", entrance: "", floor: "", landmark: "", isNew: true,
});

/* ── phone ─────────────────────────────────────────────────────────────────
 * Callers say nine digits and never the country code, so the field holds the
 * national part and prints the +998 itself. */
const digits = (s) => (s || "").replace(/\D/g, "");
const national = (s) => {
  const d = digits(s);
  return (d.startsWith("998") ? d.slice(3) : d).slice(0, 9);
};
const printPhone = (nat) => {
  const p = [nat.slice(0, 2), nat.slice(2, 5), nat.slice(5, 7), nat.slice(7, 9)].filter(Boolean);
  return p.length ? `+998 ${p.join(" ")}` : "";
};

/* Apostrophes are the single biggest source of a failed menu search here —
 * "qoy" must find "Qo'y kabob". */
const fold = (s) => s.toLowerCase().replace(/['`’]/g, "");

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/* ── local primitives ──────────────────────────────────────────────────────
 * components.jsx has no text field with a label, no segmented control and no
 * quantity stepper, and it is shared, so these three live here.
 */

function Field({ label, value, onChange, placeholder, onKeyDown, inputRef, width, autoFocus, big }) {
  const [on, setOn] = useState(false);
  return (
    <label style={{ display: "block", minWidth: 0, width }}>
      {label ? (
        <span className="q-caption" style={{ color: inkSubtle, display: "block", marginBottom: 4 }}>
          {label}
        </span>
      ) : null}
      <input
        ref={inputRef}
        value={value}
        autoFocus={autoFocus}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        onFocus={() => setOn(true)}
        onBlur={() => setOn(false)}
        placeholder={placeholder}
        className={big ? "q-body" : "q-body-sm"}
        style={{
          width: "100%", height: big ? 40 : 32, padding: "0 8px",
          background: surface1, color: ink, border: "none",
          borderBottom: on ? `2px solid ${blue}` : `1px solid ${inkSubtle}`,
          outline: "none", borderRadius: 0,
        }}
      />
    </label>
  );
}

function Segmented({ label, value, onChange, options }) {
  return (
    <div style={{ minWidth: 0 }}>
      {label ? (
        <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>{label}</div>
      ) : null}
      <div style={{ display: "flex", border: `1px solid ${hairline}` }}>
        {options.map((o, i) => {
          const on = o.value === value;
          return (
            <button
              key={o.value}
              type="button"
              onClick={() => onChange(o.value)}
              className={on ? "q-emphasis" : "q-body-sm"}
              style={{
                flex: 1, height: 32, padding: "0 10px", cursor: "pointer",
                background: on ? ink : canvas, color: on ? "#fff" : inkMuted,
                border: "none", borderLeft: i ? `1px solid ${hairline}` : "none",
                borderRadius: 0, whiteSpace: "nowrap",
              }}
            >
              {o.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}

function Stepper({ qty, onStep }) {
  const btn = {
    width: 26, height: 26, background: canvas, color: ink,
    border: `1px solid ${hairline}`, cursor: "pointer", borderRadius: 0,
    display: "inline-flex", alignItems: "center", justifyContent: "center", lineHeight: 1,
  };
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 0 }}>
      <button type="button" className="q-body-sm" style={btn} aria-label="One less" onClick={() => onStep(-1)}>−</button>
      <span
        className="q-emphasis q-tnum"
        style={{ minWidth: 30, textAlign: "center", color: ink }}
      >
        {qty}
      </span>
      <button type="button" className="q-body-sm" style={btn} aria-label="One more" onClick={() => onStep(1)}>+</button>
    </span>
  );
}

function QtyBadge({ qty }) {
  if (!qty) return null;
  return (
    <span
      className="q-caption q-tnum"
      style={{
        minWidth: 18, height: 18, padding: "0 4px", background: blue, color: "#fff",
        display: "inline-flex", alignItems: "center", justifyContent: "center", borderRadius: 0,
      }}
    >
      {qty}
    </span>
  );
}

/* A selectable row — saved address, and nothing else so far. Selection is one
 * of the sanctioned uses of platform blue. */
function Choice({ selected, onClick, children }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        display: "block", width: "100%", textAlign: "left", cursor: "pointer",
        padding: "8px 12px", borderRadius: 0,
        background: selected ? "var(--q-info-tint)" : canvas,
        border: `1px solid ${selected ? blue : hairline}`,
      }}
    >
      {children}
    </button>
  );
}

/* ── screen ────────────────────────────────────────────────────────────────*/

export default function NewOrder({ draft, setDraft, onDone, onCancel }) {
  const d = draft || BLANK;

  /* Never replace the draft — patch it. */
  const patch = (p) =>
    setDraft((prev) => {
      const base = prev || BLANK;
      return { ...base, ...(typeof p === "function" ? p(base) : p) };
    });

  const [search, setSearch] = useState("");
  const [openLine, setOpenLine] = useState(null);
  const searchRef = useRef(null);

  const lines = d.lines || [];
  const delivery = d.fulfilment === "DELIVERY";

  /* ── customer ──────────────────────────────────────────────────────────*/

  const matches = useMemo(() => {
    const q = national(d.phone);
    if (q.length < 2) return [];
    return CUSTOMERS.filter((c) => national(c.phone).startsWith(q));
  }, [d.phone]);

  const noMatch = national(d.phone).length >= 7 && matches.length === 0;

  const selectCustomer = (c) =>
    patch((prev) => ({
      customer: c,
      phone: national(c.phone),
      address: c.addresses.find((a) => a.isDefault) || c.addresses[0] || null,
      /* Malika has nothing saved, so the fresh-address form opens for her the
         moment she is matched rather than after a "new address" tap. */
      newAddress: c.addresses.length ? null : blankAddress(),
      /* Her standing instruction rides along; the operator does not retype
         "the intercom is broken" every single week. */
      note: prev.note || c.note || "",
    }));

  const createCustomer = () =>
    patch((prev) => ({
      customer: {
        id: null, name: "", phone: `+998${national(prev.phone)}`,
        ordersCount: 0, lastOrderAt: null, note: null, addresses: [], isNew: true,
      },
      address: null,
      newAddress: blankAddress(),
    }));

  const clearCustomer = () => patch({ customer: null, address: null, newAddress: null });

  const effAddress = d.address || d.newAddress;
  const patchNewAddress = (k, v) =>
    patch((prev) => ({
      address: null,
      newAddress: { ...(prev.newAddress || blankAddress()), [k]: v },
    }));

  /* ── basket ────────────────────────────────────────────────────────────*/

  const addItem = (itemId) => {
    const it = itemById(itemId);
    if (!it || !it.available) return;
    const options = defaultOptions(itemId);
    const key = lineKey(itemId, options);
    patch((prev) => {
      const has = (prev.lines || []).some((l) => l.key === key);
      return {
        lines: has
          ? prev.lines.map((l) => (l.key === key ? { ...l, qty: l.qty + 1 } : l))
          : [...(prev.lines || []), { key, itemId, options, qty: 1 }],
      };
    });
  };

  const step = (key, delta) =>
    patch((prev) => ({
      lines: prev.lines
        .map((l) => (l.key === key ? { ...l, qty: l.qty + delta } : l))
        .filter((l) => l.qty > 0),
    }));

  const removeLine = (key) => patch((prev) => ({ lines: prev.lines.filter((l) => l.key !== key) }));

  /* Changing a modifier re-keys the line, and a re-keyed line can collide with
   * one already in the basket — two half portions are one line of two. */
  const setOptions = (key, options) => {
    const nextKey = lineKey(lines.find((l) => l.key === key).itemId, options);
    patch((prev) => {
      const merged = [];
      prev.lines.forEach((l) => {
        const next = l.key === key ? { ...l, key: nextKey, options } : l;
        const dupe = merged.find((m) => m.key === next.key);
        if (dupe) dupe.qty += next.qty;
        else merged.push(next);
      });
      return { lines: merged };
    });
    setOpenLine(nextKey);
  };

  const qtyOf = (itemId) =>
    lines.filter((l) => l.itemId === itemId).reduce((s, l) => s + l.qty, 0);

  const subtotal = lines.reduce((s, l) => s + unitOf(l.itemId, l.options) * l.qty, 0);
  const deliveryFee = delivery && lines.length ? DELIVERY_FEE_MINOR : 0;
  const total = subtotal + deliveryFee;

  /* ── promise ───────────────────────────────────────────────────────────
   * Stations cook in parallel, so the basket's prep is its slowest dish, not
   * the sum; each extra line still costs the pass a little assembly. */
  const promise = useMemo(() => {
    if (!lines.length) return null;
    const prep = Math.max(...lines.map((l) => itemById(l.itemId).prepMinutes));
    const assembly = 2 * (lines.length - 1);
    const out = delivery ? RIDE_MINUTES : PASS_MINUTES;
    const minutes = Math.ceil((prep + assembly + out) / 5) * 5;
    return { minutes, at: new Date(new Date(NOW).getTime() + minutes * 60_000).toISOString() };
  }, [lines, delivery]);

  /* ── item search ───────────────────────────────────────────────────────*/

  const q = fold(search.trim());
  const filtered = q ? MENU_ITEMS.filter((i) => fold(i.name).includes(q)) : MENU_ITEMS;
  const topMatch = filtered.find((i) => i.available);
  const popular = MENU_ITEMS.filter((i) => i.popular);

  const onSearchKey = (e) => {
    if (e.key === "Enter" && topMatch) {
      e.preventDefault();
      addItem(topMatch.id);
      setSearch("");
    }
    if (e.key === "Escape") setSearch("");
  };

  /* ── readiness ─────────────────────────────────────────────────────────*/

  const missing = [];
  if (!d.customer) missing.push("a customer");
  else if (d.customer.isNew && !d.customer.name.trim()) missing.push("a name");
  if (!lines.length) missing.push("an item");
  if (delivery && !(effAddress && effAddress.line.trim())) missing.push("an address");
  const ready = missing.length === 0;

  return (
    <div>
      <SectionHeader
        title="New order"
        description="Phone first, then items. The basket on the right is what you read back to the caller."
        right={
          <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
            <span className="q-caption q-tnum" style={{ color: inkSubtle }}>
              Taking at {dt(NOW)}
            </span>
            <Button variant="ghost" size="sm" onClick={onCancel}>Discard</Button>
          </div>
        }
      />

      <div style={{ display: "grid", gridTemplateColumns: "minmax(0, 1fr) 400px", gap: 24, alignItems: "start" }}>
        {/* ── entry ────────────────────────────────────────────────────── */}
        <div style={{ minWidth: 0, display: "flex", flexDirection: "column", gap: 16 }}>

          {/* 1 — customer */}
          <Card padded={false}>
            <PanelHead step="1" title="Customer" hint="Type the number the caller gives you" />

            <div style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12 }}>
              {!d.customer ? (
                <>
                  <div style={{ display: "flex", alignItems: "flex-end", gap: 12 }}>
                    <Field
                      label="Phone"
                      value={printPhone(national(d.phone))}
                      onChange={(v) => patch({ phone: national(v) })}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && matches.length === 1) selectCustomer(matches[0]);
                        if (e.key === "Enter" && noMatch) createCustomer();
                      }}
                      placeholder="+998 90 123 45 67"
                      width={260}
                      autoFocus
                      big
                    />
                    <span className="q-caption" style={{ color: inkSubtle, paddingBottom: 10 }}>
                      {national(d.phone).length < 2
                        ? "Nine digits, no country code"
                        : `${matches.length} match${matches.length === 1 ? "" : "es"} so far`}
                    </span>
                  </div>

                  {matches.length ? (
                    <div style={{ border: `1px solid ${hairline}` }}>
                      {matches.map((c, i) => (
                        <button
                          key={c.id}
                          type="button"
                          onClick={() => selectCustomer(c)}
                          style={{
                            display: "flex", alignItems: "center", gap: 12, width: "100%",
                            textAlign: "left", padding: "8px 12px", cursor: "pointer",
                            background: canvas, border: "none", borderRadius: 0,
                            borderTop: i ? `1px solid ${hairline}` : "none",
                          }}
                          onMouseEnter={(e) => { e.currentTarget.style.background = surface1; }}
                          onMouseLeave={(e) => { e.currentTarget.style.background = canvas; }}
                        >
                          <span className="q-emphasis" style={{ color: ink, minWidth: 160 }}>{c.name}</span>
                          <span className="q-body-sm q-tnum" style={{ color: inkMuted }}>{c.phone}</span>
                          <span className="q-caption q-tnum" style={{ color: inkSubtle, marginLeft: "auto" }}>
                            {plural(c.ordersCount, "order")} · last {day(c.lastOrderAt)}
                          </span>
                          <span className="q-caption" style={{ color: inkSubtle, minWidth: 64, textAlign: "right" }}>
                            {c.addresses.length
                              ? `${c.addresses.length} address${c.addresses.length === 1 ? "" : "es"}`
                              : "no address"}
                          </span>
                        </button>
                      ))}
                    </div>
                  ) : null}

                  {noMatch ? (
                    <div
                      style={{
                        display: "flex", alignItems: "center", gap: 12,
                        padding: "8px 12px", border: `1px solid ${hairline}`, background: surface1,
                      }}
                    >
                      <span className="q-body-sm" style={{ color: ink }}>
                        No customer on {printPhone(national(d.phone))}
                      </span>
                      <Button variant="tertiary" size="sm" onClick={createCustomer} style={{ marginLeft: "auto" }}>
                        Create and keep going
                      </Button>
                    </div>
                  ) : null}
                </>
              ) : (
                <>
                  <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
                    {d.customer.isNew ? (
                      <Field
                        label="Name"
                        value={d.customer.name}
                        onChange={(v) => patch((prev) => ({ customer: { ...prev.customer, name: v } }))}
                        placeholder="Ism familiya"
                        width={240}
                        autoFocus
                      />
                    ) : (
                      <span className="q-emphasis" style={{ color: ink }}>{d.customer.name}</span>
                    )}
                    <span className="q-body-sm q-tnum" style={{ color: inkMuted }}>{d.customer.phone}</span>
                    {d.customer.isNew ? (
                      <StatusPill tone="info">New customer</StatusPill>
                    ) : (
                      <>
                        <StatusPill tone="neutral">{plural(d.customer.ordersCount, "order")}</StatusPill>
                        <span className="q-caption q-tnum" style={{ color: inkSubtle }}>
                          Last {day(d.customer.lastOrderAt)}
                        </span>
                      </>
                    )}
                    <Button variant="ghost" size="sm" onClick={clearCustomer} style={{ marginLeft: "auto" }}>
                      Change number
                    </Button>
                  </div>

                  {d.customer.note ? (
                    <div className="q-body-sm" style={{ color: "var(--q-warning-text)", background: "var(--q-warning-tint)", padding: "6px 12px" }}>
                      Standing note · {d.customer.note}
                    </div>
                  ) : null}

                  {delivery ? (
                    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
                      <div className="q-caption" style={{ color: inkSubtle }}>Deliver to</div>

                      {d.customer.addresses.map((a) => (
                        <Choice key={a.id} selected={d.address?.id === a.id} onClick={() => patch({ address: a })}>
                          <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                            <span className="q-emphasis" style={{ color: ink }}>{a.label}</span>
                            <span className="q-body-sm" style={{ color: ink }}>{a.line}</span>
                            <span className="q-caption q-tnum" style={{ color: inkSubtle, marginLeft: "auto" }}>
                              {[a.entrance && `pod. ${a.entrance}`, a.floor && `${a.floor}-qavat`, a.landmark]
                                .filter(Boolean).join(" · ")}
                            </span>
                          </div>
                        </Choice>
                      ))}

                      <Choice
                        selected={!d.address}
                        onClick={() => patch((prev) => ({ address: null, newAddress: prev.newAddress || blankAddress() }))}
                      >
                        <span className="q-body-sm" style={{ color: ink }}>
                          {d.customer.addresses.length ? "Somewhere else — type it" : "No saved address — type it"}
                        </span>
                      </Choice>

                      {!d.address ? (
                        <div style={{ display: "grid", gridTemplateColumns: "1.6fr 72px 72px 1fr", gap: 12 }}>
                          <Field
                            label="Street and flat"
                            value={d.newAddress?.line || ""}
                            onChange={(v) => patchNewAddress("line", v)}
                            placeholder="Ko'cha, uy, xonadon"
                          />
                          <Field label="Entrance" value={d.newAddress?.entrance || ""} onChange={(v) => patchNewAddress("entrance", v)} />
                          <Field label="Floor" value={d.newAddress?.floor || ""} onChange={(v) => patchNewAddress("floor", v)} />
                          <Field
                            label="Landmark"
                            value={d.newAddress?.landmark || ""}
                            onChange={(v) => patchNewAddress("landmark", v)}
                            placeholder="Mo'ljal"
                          />
                        </div>
                      ) : null}
                    </div>
                  ) : (
                    <div className="q-body-sm" style={{ color: inkMuted }}>
                      Pickup — no address needed. The caller collects at {promise ? dt(promise.at) : "the promise time"}.
                    </div>
                  )}
                </>
              )}
            </div>
          </Card>

          {/* 2 — items */}
          <Card padded={false}>
            <PanelHead
              step="2"
              title="Items"
              hint="Enter adds the top match"
              right={
                <input
                  ref={searchRef}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={onSearchKey}
                  placeholder="Search the menu"
                  className="q-body-sm"
                  style={{
                    width: 260, height: 32, padding: "0 8px", background: canvas, color: ink,
                    border: "none", borderBottom: `1px solid ${inkSubtle}`, outline: "none", borderRadius: 0,
                  }}
                  onFocus={(e) => { e.target.style.borderBottom = `2px solid ${blue}`; }}
                  onBlur={(e) => { e.target.style.borderBottom = `1px solid ${inkSubtle}`; }}
                />
              }
            />

            {/* The six dishes that are most of the volume. One tap, no typing. */}
            <div style={{ padding: 16, borderBottom: `1px solid ${hairline}` }}>
              <div className="q-caption" style={{ color: inkSubtle, marginBottom: 8 }}>Most ordered</div>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(176px, 1fr))", gap: 8 }}>
                {popular.map((i) => (
                  <button
                    key={i.id}
                    type="button"
                    onClick={() => addItem(i.id)}
                    style={{
                      display: "flex", alignItems: "center", gap: 8, padding: "10px 12px",
                      background: canvas, border: `1px solid ${hairline}`, borderRadius: 0,
                      cursor: "pointer", textAlign: "left", minHeight: 48,
                    }}
                    onMouseEnter={(e) => { e.currentTarget.style.background = surface1; }}
                    onMouseLeave={(e) => { e.currentTarget.style.background = canvas; }}
                  >
                    <span style={{ minWidth: 0 }}>
                      <span className="q-body-sm" style={{ color: ink, display: "block" }}>{i.name}</span>
                      <span className="q-caption q-tnum" style={{ color: inkSubtle }}>
                        {uzs(i.priceMinor)} · {i.prepMinutes} min
                      </span>
                    </span>
                    <span style={{ marginLeft: "auto" }}><QtyBadge qty={qtyOf(i.id)} /></span>
                  </button>
                ))}
              </div>
            </div>

            <div style={{ maxHeight: 360, overflowY: "auto" }}>
              {q ? (
                <ItemRows items={filtered} topId={topMatch?.id} showCategory qtyOf={qtyOf} onAdd={addItem} />
              ) : (
                CATEGORIES.map((c) => {
                  const rows = filtered.filter((i) => i.categoryId === c.id);
                  if (!rows.length) return null;
                  return (
                    <div key={c.id}>
                      <div
                        className="q-caption"
                        style={{
                          padding: "6px 16px", background: surface1, color: inkMuted,
                          borderBottom: `1px solid ${hairline}`, position: "sticky", top: 0,
                        }}
                      >
                        {c.name}
                      </div>
                      <ItemRows items={rows} qtyOf={qtyOf} onAdd={addItem} />
                    </div>
                  );
                })
              )}
              {q && !filtered.length ? (
                <div className="q-body-sm" style={{ padding: 24, color: inkMuted, textAlign: "center" }}>
                  Nothing on the menu matches “{search}”.
                </div>
              ) : null}
            </div>
          </Card>
        </div>

        {/* ── the order ────────────────────────────────────────────────── */}
        <div style={{ position: "sticky", top: 72 }}>
          <Card padded={false}>
            <PanelHead
              step="3"
              title="Basket"
              hint={lines.length ? plural(lines.reduce((s, l) => s + l.qty, 0), "item") : "Empty"}
              right={
                lines.length ? (
                  <Button variant="ghost" size="sm" onClick={() => patch({ lines: [] })}>Clear</Button>
                ) : null
              }
            />

            <div style={{ maxHeight: 300, overflowY: "auto" }}>
              {!lines.length ? (
                <div className="q-body-sm" style={{ padding: 24, color: inkMuted, textAlign: "center" }}>
                  Tap a popular item, or type a name and press Enter.
                </div>
              ) : (
                lines.map((l) => (
                  <BasketLine
                    key={l.key}
                    line={l}
                    open={openLine === l.key}
                    onToggle={() => setOpenLine(openLine === l.key ? null : l.key)}
                    onStep={(delta) => step(l.key, delta)}
                    onRemove={() => removeLine(l.key)}
                    onOptions={(opts) => setOptions(l.key, opts)}
                  />
                ))
              )}
            </div>

            {/* 4 — fulfilment, payment and note. One row, never a wizard step:
                an operator on a call cannot press next. */}
            <div style={{ padding: 16, borderTop: `1px solid ${hairline}`, display: "flex", flexDirection: "column", gap: 12 }}>
              <div style={{ display: "flex", gap: 12 }}>
                <Segmented
                  label="Fulfilment"
                  value={d.fulfilment}
                  onChange={(v) => patch({ fulfilment: v })}
                  options={[{ value: "DELIVERY", label: "Delivery" }, { value: "PICKUP", label: "Pickup" }]}
                />
                <Segmented
                  label="Payment"
                  value={d.payment}
                  onChange={(v) => patch({ payment: v })}
                  options={[
                    { value: "CASH", label: "Cash" },
                    { value: "CLICK", label: "Click" },
                    { value: "PAYME", label: "Payme" },
                  ]}
                />
              </div>
              <Field
                label="Note for the kitchen and the courier"
                value={d.note}
                onChange={(v) => patch({ note: v })}
                placeholder="Achchiq qilmang, domofon ishlamaydi…"
              />
            </div>

            <div style={{ padding: 16, borderTop: `1px solid ${hairline}` }}>
              <Money label="Subtotal" value={subtotal} />
              <Money label={delivery ? "Delivery" : "Pickup"} value={deliveryFee} />

              <div style={{ display: "flex", alignItems: "flex-end", gap: 12, marginTop: 8, paddingTop: 8, borderTop: `1px solid ${hairline}` }}>
                <div style={{ minWidth: 0 }}>
                  <div className="q-caption" style={{ color: inkSubtle }}>Promise</div>
                  <div className="q-emphasis q-tnum" style={{ color: promise ? ink : inkSubtle }}>
                    {promise ? `${dt(promise.at)} · ${promise.minutes} min` : "—"}
                  </div>
                </div>
                <div style={{ marginLeft: "auto", textAlign: "right" }}>
                  <div className="q-caption" style={{ color: inkSubtle }}>Total</div>
                  <div className="q-data-lg" style={{ color: ink }}>{uzs(total)}</div>
                </div>
              </div>

              <Button
                variant="primary"
                onClick={onDone}
                disabled={!ready}
                style={{ width: "100%", marginTop: 12, height: 48 }}
              >
                {ready ? `Confirm order · ${uzs(total)}` : "Confirm order"}
              </Button>
              {!ready ? (
                <div className="q-caption" style={{ color: inkMuted, marginTop: 6, textAlign: "center" }}>
                  Still needs {missing.join(", ")}.
                </div>
              ) : (
                <div className="q-caption" style={{ color: inkMuted, marginTop: 6, textAlign: "center" }}>
                  Read back: {plural(lines.reduce((s, l) => s + l.qty, 0), "item")},{" "}
                  {delivery ? "delivery" : "pickup"}, {uzs(total)} by {d.payment.toLowerCase()}.
                </div>
              )}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}

/* ── panel furniture ───────────────────────────────────────────────────────*/

function PanelHead({ step, title, hint, right }) {
  return (
    <div
      style={{
        display: "flex", alignItems: "center", gap: 12, padding: "10px 16px",
        borderBottom: `1px solid ${hairline}`, background: canvas,
      }}
    >
      <span
        className="q-caption q-tnum"
        style={{
          width: 20, height: 20, background: surface1, color: inkMuted, borderRadius: 0,
          display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0,
        }}
      >
        {step}
      </span>
      <span className="q-emphasis" style={{ color: ink }}>{title}</span>
      {hint ? <span className="q-caption" style={{ color: inkSubtle }}>{hint}</span> : null}
      {right ? <span style={{ marginLeft: "auto" }}>{right}</span> : null}
    </div>
  );
}

function Money({ label, value }) {
  return (
    <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
      <span className="q-body-sm" style={{ color: inkMuted }}>{label}</span>
      <span className="q-body-sm q-tnum" style={{ color: ink, marginLeft: "auto" }}>{uzs(value)}</span>
    </div>
  );
}

/* ── menu rows ─────────────────────────────────────────────────────────────
 * A sold-out dish stays in the list. Hiding it makes the operator say "I can't
 * find it"; showing it greyed with the kitchen's reason lets them say "the
 * mince has run out, shall I do you a tovuq kabob instead".
 */
function ItemRows({ items, topId, showCategory, qtyOf, onAdd }) {
  return items.map((i) => {
    const out = !i.available;
    /* Only the required group is named. It is the one that moves the price, and
     * the row has to stay one line at 40px. */
    const sized = groupsFor(i.id).find((g) => g.required);
    const qty = qtyOf(i.id);
    const top = i.id === topId;
    return (
      <div
        key={i.id}
        role={out ? undefined : "button"}
        onClick={out ? undefined : () => onAdd(i.id)}
        style={{
          display: "flex", alignItems: "center", gap: 12, padding: "0 16px", height: 40,
          borderBottom: `1px solid ${hairline}`,
          background: top ? "var(--q-info-tint)" : canvas,
          cursor: out ? "not-allowed" : "pointer",
          opacity: out ? 0.65 : 1,
        }}
        onMouseEnter={(e) => { if (!out && !top) e.currentTarget.style.background = surface1; }}
        onMouseLeave={(e) => { if (!out && !top) e.currentTarget.style.background = canvas; }}
      >
        <span
          className="q-body-sm"
          style={{ color: ink, minWidth: 0, flex: 1, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}
        >
          {i.name}
        </span>

        {showCategory ? (
          <span className="q-caption" style={{ color: inkSubtle, width: 120, flexShrink: 0 }}>
            {categoryName(i.categoryId)}
          </span>
        ) : null}
        <span className="q-caption" style={{ color: inkSubtle, width: 64, flexShrink: 0, whiteSpace: "nowrap" }}>
          {sized ? sized.name : ""}
        </span>
        <span className="q-caption q-tnum" style={{ color: inkSubtle, width: 48, textAlign: "right", flexShrink: 0 }}>
          {i.prepMinutes} min
        </span>
        <span className="q-body-sm q-tnum" style={{ color: ink, width: 100, textAlign: "right", flexShrink: 0 }}>
          {uzs(i.priceMinor)}
        </span>
        <span style={{ minWidth: 96, display: "flex", justifyContent: "flex-end", flexShrink: 0 }}>
          {out ? (
            <StatusPill tone="failed">{i.soldOutReason}</StatusPill>
          ) : qty ? (
            <QtyBadge qty={qty} />
          ) : (
            <span className="q-caption" style={{ color: top ? "var(--q-info-text)" : inkSubtle }}>
              {top ? "Enter" : "Add"}
            </span>
          )}
        </span>
      </div>
    );
  });
}

/* ── basket line ───────────────────────────────────────────────────────────
 * The default modifier is already applied, so the row reads "Toshkent oshi ·
 * To'liq" without anyone having chosen anything. Opening it is only for the
 * caller who wants a half portion or qazi on top.
 */
function BasketLine({ line, open, onToggle, onStep, onRemove, onOptions }) {
  const item = itemById(line.itemId);
  const groups = groupsFor(line.itemId);
  const chosen = chosenOptions(line.itemId, line.options);
  const unit = unitOf(line.itemId, line.options);

  const toggleOption = (group, optionId) => {
    const others = line.options.filter((id) => !group.options.some((o) => o.id === id));
    const mine = line.options.filter((id) => group.options.some((o) => o.id === id));
    if (group.max === 1) return onOptions([...others, optionId]);
    const next = mine.includes(optionId) ? mine.filter((id) => id !== optionId) : [...mine, optionId];
    onOptions([...others, ...next]);
  };

  return (
    <div style={{ borderBottom: `1px solid ${hairline}` }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 16px" }}>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="q-body-sm" style={{ color: ink }}>{item.name}</div>
          <button
            type="button"
            onClick={groups.length ? onToggle : undefined}
            className="q-caption"
            style={{
              padding: 0, background: "transparent", border: "none", borderRadius: 0,
              color: groups.length ? blue : inkSubtle,
              cursor: groups.length ? "pointer" : "default", textAlign: "left",
            }}
          >
            {chosen.length
              ? `${chosen.map((o) => o.name).join(", ")}${open ? " — close" : " — change"}`
              : groups.length
                ? (open ? "Close extras" : "Add extras")
                : uzs(unit)}
          </button>
        </div>

        <Stepper qty={line.qty} onStep={onStep} />

        <span className="q-body-sm q-tnum" style={{ color: ink, width: 96, textAlign: "right" }}>
          {uzs(unit * line.qty)}
        </span>

        <button
          type="button"
          onClick={onRemove}
          aria-label={`Remove ${item.name}`}
          className="q-body-sm"
          style={{ background: "transparent", border: "none", color: inkSubtle, cursor: "pointer", padding: 4, borderRadius: 0 }}
        >
          ✕
        </button>
      </div>

      {open && groups.length ? (
        <div style={{ padding: "0 16px 12px", display: "flex", flexDirection: "column", gap: 8 }}>
          {groups.map((g) => (
            <div key={g.id}>
              <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>
                {g.name}{g.required ? "" : " — optional"}
              </div>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                {g.options.map((o) => {
                  const on = line.options.includes(o.id);
                  return (
                    <button
                      key={o.id}
                      type="button"
                      onClick={() => toggleOption(g, o.id)}
                      className="q-caption"
                      style={{
                        padding: "4px 8px", borderRadius: 0, cursor: "pointer",
                        background: on ? "var(--q-info-tint)" : canvas,
                        color: on ? "var(--q-info-text)" : inkMuted,
                        border: `1px solid ${on ? blue : hairline}`,
                      }}
                    >
                      {o.name}
                      {o.priceDeltaMinor ? (
                        <span className="q-tnum">
                          {" "}{o.priceDeltaMinor > 0 ? "+" : ""}{uzs(o.priceDeltaMinor)}
                        </span>
                      ) : null}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
