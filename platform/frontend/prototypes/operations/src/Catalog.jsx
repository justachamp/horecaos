/* Catalog — the menu, as one restaurant's staff manage it.
 *
 * The structural decision this file exists to make, taken from catalog.md §0:
 * **the landing depends on the capability set, not on a fixed route.** Catalog
 * serves two people who share no working conditions.
 *
 *   The author.  Seated, an afternoon, 200–1500 items. Wants density, bulk
 *   edit, and every problem listed at once. Tolerates a five-step publication
 *   because publishing is a deliberate act. Lands on Products.
 *
 *   The service operator.  Standing, one hand free, mid-rush. Needs "osh —
 *   stop" to be true on every channel within a second. Tolerates nothing: no
 *   modal, no confirm, no save button. Lands on the stop list and never sees
 *   the authoring tree at all.
 *
 * So the two are different surfaces with different write paths, and the write
 * paths are the reason. Authoring writes drafts that reach a customer only
 * through a validated publication. Availability writes location_offerings and
 * inventory.positions and takes effect immediately — setOffering says so in its
 * own Javadoc: *marking a dish sold out must not require re-validating an
 * entire menu*. The UI must not blur back together what the backend separated.
 *
 * A prototype has no session, so the capability set is a visible control in the
 * header rather than a claim. Switch it and the whole section changes shape.
 *
 * Four views are built: Products (4.1), Menus layer A (4.5), Availability and
 * the stop list (4.6), Publication (4.10). What is not built is listed at the
 * bottom of this file rather than left as a silence.
 */

import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { NOW } from "./data";
import {
  ink, inkMuted, inkSubtle, hairline, surface1, canvas, blue,
  uzs, dt,
  StatusPill, Button, Select, SearchInput, EmptyState, Drawer, Card,
} from "./components";
import {
  BRAND, LOCATIONS, LOC, CHANNELS, CAT_CATEGORIES, CAT, ACTORS,
  STOP_REASONS, REASON_LABEL, PRODUCTS, MOVEMENTS, ROLES, NOT_BUILT,
  LIVE_PUBLICATIONS, PUBLICATION_HISTORY, CATALOG_WARNINGS, FINDING_TEXT,
  activeVariants, blockersFor, warningsFor, severityOf, inMenuCount,
  priceRange, categoryPath, menuOrder, variantRowsAt, explain, since, minutesBetween,
  modesFor, MODE_SHORT, MODES_DEFAULT,
} from "./Catalog.data";

/* ── severity ──────────────────────────────────────────────────────────────
 * Three channels at once, never colour alone: a 3px left rule, a background
 * tint, and a caption naming the actual reason. A normal row carries a
 * transparent rule of the same width so nothing shifts between bands.
 */
const SEV = {
  blocker: { rule: "var(--q-error)", tint: "var(--q-error-tint)", text: "var(--q-error-text)" },
  warn: { rule: "var(--q-warning)", tint: "var(--q-warning-tint)", text: "var(--q-warning-text)" },
  none: { rule: "transparent", tint: "transparent", text: inkMuted },
};

const MONO = { fontFamily: "var(--q-font-mono)" };
const cell = { padding: "10px 12px", verticalAlign: "middle", borderBottom: `1px solid ${hairline}` };

function Th({ children, align = "left", width }) {
  return (
    <th
      className="q-caption"
      style={{
        textAlign: align, padding: "10px 12px", background: surface1, color: inkMuted,
        fontWeight: 600, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap", width,
        position: "sticky", top: 0, zIndex: 1,
      }}
    >
      {children}
    </th>
  );
}

/* A photo cell. A hairline placeholder box, never an emoji, and it says what is
 * wrong with the asset when the asset is what is wrong. */
function Thumb({ status, size = 32 }) {
  const bad = status && status !== "AVAILABLE";
  return (
    <span
      title={status || "No photo"}
      style={{
        display: "inline-block", width: size, height: size, flexShrink: 0,
        border: `1px ${status ? "solid" : "dashed"} ${bad ? "var(--q-error)" : hairline}`,
        background: status === "AVAILABLE" ? surface1 : canvas,
      }}
    />
  );
}

function Chip({ children, tone = "neutral" }) {
  const map = {
    neutral: { bg: surface1, fg: inkMuted, bd: hairline },
    warn: { bg: "var(--q-warning-tint)", fg: "var(--q-warning-text)", bd: "var(--q-warning)" },
    error: { bg: "var(--q-error-tint)", fg: "var(--q-error-text)", bd: "var(--q-error)" },
    info: { bg: "var(--q-info-tint)", fg: "var(--q-info-text)", bd: blue },
  }[tone];
  return (
    <span
      className="q-caption"
      style={{
        display: "inline-block", padding: "1px 6px", background: map.bg,
        color: map.fg, border: `1px solid ${map.bd}`, whiteSpace: "nowrap",
      }}
    >
      {children}
    </span>
  );
}

function Link({ children, onClick }) {
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      className="q-body-sm"
      style={{ background: "none", border: "none", padding: 0, color: blue, cursor: "pointer", textAlign: "left" }}
    >
      {children}
    </button>
  );
}

/* Where a view needs data the backend does not have, it says so on the screen
 * and names the ADR. Nothing here is faked with a boolean and a comment. */
function NotBuilt({ children }) {
  return (
    <div
      className="q-caption"
      style={{
        padding: "8px 12px", background: surface1, color: inkMuted,
        borderLeft: `3px solid ${inkSubtle}`,
      }}
    >
      Not built — {children}
    </div>
  );
}

function Banner({ tone = "warn", title, children, action }) {
  const map = {
    warn: { bg: "var(--q-warning-tint)", fg: "var(--q-warning-text)", rule: "var(--q-warning)" },
    error: { bg: "var(--q-error-tint)", fg: "var(--q-error-text)", rule: "var(--q-error)" },
    info: { bg: "var(--q-info-tint)", fg: "var(--q-info-text)", rule: blue },
  }[tone];
  return (
    <div style={{ display: "flex", gap: 12, alignItems: "flex-start", background: map.bg, borderLeft: `3px solid ${map.rule}`, padding: "10px 12px" }}>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div className="q-emphasis" style={{ color: map.fg }}>{title}</div>
        {children ? <div className="q-caption" style={{ color: map.fg, marginTop: 2 }}>{children}</div> : null}
      </div>
      {action}
    </div>
  );
}

/* Confirmation is required only for irreversible or wide-blast actions, and the
 * dialog names the object rather than asking "are you sure". Modal state is the
 * id of the record acted on, never a boolean. */
function Confirm({ title, body, confirmLabel, danger, onConfirm, onClose, children }) {
  const ref = useRef(null);
  useEffect(() => {
    /* Focus is trapped in the dialog and restored on close in the real thing;
     * here it is moved into the dialog so Escape and Tab behave. */
    ref.current?.focus();
    const esc = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [onClose]);
  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 60 }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        ref={ref}
        tabIndex={-1}
        style={{ width: 480, maxWidth: "100%", background: canvas, border: `1px solid ${hairline}`, padding: 24, outline: "none" }}
      >
        <div className="q-subhead" style={{ color: ink }}>{title}</div>
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 8 }}>{body}</div>
        {children ? <div style={{ marginTop: 16 }}>{children}</div> : null}
        <div style={{ display: "flex", gap: 8, marginTop: 24, justifyContent: "flex-end" }}>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant={danger ? "danger" : "primary"} onClick={onConfirm}>{confirmLabel}</Button>
        </div>
      </div>
    </div>
  );
}

/* ── the section ───────────────────────────────────────────────────────────*/

export default function Catalog(props) {
  /* App renders <Catalog /> today, so the section owns its own state when the
   * shell does not hoist it. The prop shape is the contract either way. */
  const [ownView, setOwnView] = useState(null);
  const [ownProduct, setOwnProduct] = useState(null);
  const productId = props.productId !== undefined ? props.productId : ownProduct;
  const setProductId = props.setProductId || setOwnProduct;

  const [roleId, setRoleId] = useState("service");
  const role = ROLES.find((r) => r.id === roleId);
  const may = (cap) => role.caps.includes(cap);

  const rawView = props.view !== undefined ? props.view : ownView;
  const setRawView = props.setView || setOwnView;
  /* The landing follows from the capability set, and a view outside the set is
   * not merely hidden — it is not reachable. */
  const view = role.views.includes(rawView) ? rawView : role.landing;
  const setView = (v) => setRawView(v);

  const [locationId, setLocationId] = useState("chi");

  /* One edit map for the whole section, keyed `variantId@locationId` exactly as
   * catalog.location_offerings is keyed. Stopping a dish on the stop list is
   * the same write the matrix cell makes, so both views show it at once. */
  const [edits, setEdits] = useState({});
  const [undo, setUndo] = useState(null);

  const offeringAt = (variant, loc) => {
    const e = edits[`${variant.id}@${loc}`];
    return e ? e.offering : (variant.at[loc] ?? null);
  };
  const movementAt = (variant, loc) => {
    const e = edits[`${variant.id}@${loc}`];
    return e ? e.movement : (MOVEMENTS[`${variant.id}@${loc}`] || null);
  };

  const writeOffering = (variant, loc, offering, reason) => {
    const key = `${variant.id}@${loc}`;
    const prev = { offering: offeringAt(variant, loc), movement: movementAt(variant, loc) };
    setEdits((m) => ({
      ...m,
      [key]: {
        offering,
        movement: offering === "UNAVAILABLE"
          ? { at: NOW, actorId: "u-aziz", reason: reason || null }
          : null,
      },
    }));
    return { key, prev };
  };

  /* The tap and the reason are two moments, not one form. The tap is the whole
   * gesture; the reason is offered afterwards on the bar the row's disappearance
   * leaves behind — asking on the row itself does not work, because the row has
   * already moved to the other tab by the time the chips would render. */
  const stop = (variant, loc, label, reason) =>
    setUndo({ ...writeOffering(variant, loc, "UNAVAILABLE", reason), label, kind: "stop", variant, loc, reason: reason || null });
  const resume = (variant, loc, label) =>
    setUndo({ ...writeOffering(variant, loc, "AVAILABLE"), label, kind: "resume" });
  const setReason = (code) => {
    if (!undo || undo.kind !== "stop") return;
    writeOffering(undo.variant, undo.loc, "UNAVAILABLE", code);
    setUndo({ ...undo, reason: code });
  };
  const undoLast = () => {
    if (!undo) return;
    setEdits((m) => ({ ...m, [undo.key]: undo.prev }));
    setUndo(null);
  };

  const VIEW_TITLE = {
    products: "Products",
    menus: "Menus",
    availability: "Availability and stop list",
    publication: "Publication",
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
      <ScopeStrip
        title={VIEW_TITLE[view]}
        role={role} roleId={roleId} setRoleId={(id) => { setRoleId(id); setRawView(null); }}
      />

      {role.views.length > 1 ? (
        <div style={{ display: "flex", borderBottom: `1px solid ${hairline}` }}>
          {role.views.map((v) => (
            <button
              key={v}
              type="button"
              onClick={() => setView(v)}
              className="q-body-sm"
              style={{
                padding: "10px 16px", background: "transparent", border: "none", marginBottom: -1,
                borderBottom: v === view ? `2px solid ${blue}` : "2px solid transparent",
                color: v === view ? ink : inkMuted, cursor: "pointer",
              }}
            >
              {VIEW_TITLE[v]}
            </button>
          ))}
        </div>
      ) : null}

      {view === "products" ? (
        <ProductsView may={may} productId={productId} setProductId={setProductId} setView={setView} offeringAt={offeringAt} />
      ) : null}
      {view === "menus" ? (
        <MenusView may={may} offeringAt={offeringAt} writeOffering={writeOffering} />
      ) : null}
      {view === "availability" ? (
        <AvailabilityView
          may={may} locationId={locationId} setLocationId={setLocationId}
          offeringAt={offeringAt} movementAt={movementAt}
          stop={stop} resume={resume} setView={setView} reachable={role.views}
        />
      ) : null}
      {view === "publication" ? (
        <PublicationView may={may} setView={setView} setProductId={setProductId} />
      ) : null}

      {undo ? (
        <div
          style={{
            position: "fixed", left: 24, bottom: 24, zIndex: 40, background: "var(--q-inverse)",
            color: "var(--q-inverse-ink)", padding: "10px 12px", display: "flex", alignItems: "center", gap: 16,
          }}
        >
          <span className="q-body-sm">
            {undo.label} {undo.kind === "resume" ? "is back on the menu" : "is on stop"}
            {undo.reason ? ` · ${REASON_LABEL[undo.reason]}` : ""}
          </span>
          {undo.kind === "stop" && !undo.reason ? (
            <span style={{ display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
              <span className="q-caption" style={{ color: "var(--q-inverse-ink-muted)" }}>Reason, optional</span>
              {STOP_REASONS.map((r) => (
                <button
                  key={r.code} type="button" onClick={() => setReason(r.code)} className="q-caption"
                  style={{
                    padding: "6px 10px", minHeight: 32, background: "transparent",
                    border: "1px solid var(--q-inverse-ink-muted)", color: "#fff", cursor: "pointer",
                  }}
                >
                  {r.label}
                </button>
              ))}
            </span>
          ) : null}
          <button
            type="button"
            onClick={undoLast}
            className="q-emphasis"
            style={{ background: "none", border: "none", color: "#fff", cursor: "pointer", textDecoration: "underline" }}
          >
            Undo
          </button>
          <button
            type="button"
            onClick={() => setUndo(null)}
            className="q-caption"
            style={{ background: "none", border: "none", color: "var(--q-inverse-ink-muted)", cursor: "pointer" }}
          >
            Dismiss
          </button>
        </div>
      ) : null}
    </div>
  );
}

/* ── scope strip ───────────────────────────────────────────────────────────
 * Brand and location go into the query, never into a client-side filter, so
 * they sit in the header beside the title rather than in the filter bar with
 * the things that do filter.
 */
function ScopeStrip({ title, role, roleId, setRoleId }) {
  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", gap: 16, flexWrap: "wrap" }}>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>{title}</h1>
          <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
            {BRAND.name} · brand locale {BRAND.defaultLocale.toUpperCase()} · {LOCATIONS.length} branches
          </div>
        </div>
        <div style={{ marginLeft: "auto", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
          <Select
            label="Brand"
            value="b-osh"
            onChange={() => {}}
            options={[{ value: "b-osh", label: BRAND.name }]}
          />
          <Select
            label="Signed in as"
            value={roleId}
            onChange={setRoleId}
            options={ROLES.map((r) => ({ value: r.id, label: r.label }))}
          />
        </div>
      </div>
      <div
        className="q-caption"
        style={{ marginTop: 12, padding: "8px 12px", background: surface1, color: inkMuted, borderLeft: `3px solid ${blue}` }}
      >
        {role.note} Capabilities: <span style={MONO}>{role.caps.join(" · ")}</span>
      </div>
    </div>
  );
}

/* ── 4.1 Products — the brand library ──────────────────────────────────────
 * "Do we already have this dish, and is it in a state that can be sold?"
 * Dense list, persistent category rail, detail drawer. Not master-detail by
 * navigation: an author checks twenty products in a row and a page transition
 * per product costs more than the drawer's width.
 */
function ProductsView({ may, productId, setProductId, setView, offeringAt }) {
  const [tab, setTab] = useState("all");
  const [cats, setCats] = useState([]);
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState("state");
  const [selected, setSelected] = useState([]);
  const [menuFor, setMenuFor] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const author = may("CATALOG_AUTHOR");

  /* Counts are computed before filtering, because a tab whose count moves with
   * the filter cannot be used to decide what to look at next. */
  const counts = useMemo(() => ({
    all: PRODUCTS.filter((p) => p.status !== "ARCHIVED").length,
    active: PRODUCTS.filter((p) => p.status === "ACTIVE").length,
    draft: PRODUCTS.filter((p) => p.status === "DRAFT").length,
    archived: PRODUCTS.filter((p) => p.status === "ARCHIVED").length,
    unpublished: PRODUCTS.filter((p) => !p.published && p.status !== "ARCHIVED").length,
    nomxik: PRODUCTS.filter((p) => warningsFor(p).includes("FISCAL_CLASSIFICATION_MISSING")).length,
    nophoto: PRODUCTS.filter((p) => warningsFor(p).includes("NO_PHOTO")).length,
  }), []);

  const TABS = [
    { id: "all", label: "All", n: counts.all },
    { id: "active", label: "Active", n: counts.active },
    { id: "draft", label: "Drafts", n: counts.draft },
    { id: "archived", label: "Archive", n: counts.archived },
    { id: "unpublished", label: "Not published", n: counts.unpublished },
    { id: "nomxik", label: "No ИКПУ", n: counts.nomxik },
    { id: "nophoto", label: "No photo", n: counts.nophoto },
  ];

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    let out = PRODUCTS.filter((p) => {
      if (tab === "active" && p.status !== "ACTIVE") return false;
      if (tab === "draft" && p.status !== "DRAFT") return false;
      if (tab === "archived" && p.status !== "ARCHIVED") return false;
      if (tab === "all" && p.status === "ARCHIVED") return false;
      if (tab === "unpublished" && (p.published || p.status === "ARCHIVED")) return false;
      if (tab === "nomxik" && !warningsFor(p).includes("FISCAL_CLASSIFICATION_MISSING")) return false;
      if (tab === "nophoto" && !warningsFor(p).includes("NO_PHOTO")) return false;
      if (cats.length && !p.categoryIds.some((c) => cats.includes(c))) return false;
      if (q) {
        const hay = `${p.name} ${p.nameRu} ${p.code} ${p.variants.map((v) => v.sku).join(" ")}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
    /* Default sort is state severity, then name: an author opens this screen to
     * find what is wrong, and an alphabetical menu buries it. */
    if (sort === "state") {
      out = out.sort((a, b) => severityOf(a).band - severityOf(b).band || a.name.localeCompare(b.name));
    } else if (sort === "name") {
      out = out.sort((a, b) => a.name.localeCompare(b.name));
    } else {
      out = out.sort((a, b) => (priceRange(b)?.min ?? -1) - (priceRange(a)?.min ?? -1));
    }
    return out;
  }, [tab, cats, query, sort]);

  const selectedRows = rows.filter((p) => selected.includes(p.id));
  const toggle = (id) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  /* A bulk action is offered only when it is valid for every selected row. A
   * mixed selection disables it and says how many rows are the problem — it
   * never acts on the valid subset and reports success. */
  const bulk = [
    { id: "mxik", label: "Set ИКПУ and package code", bad: [], need: author },
    {
      id: "archive", label: "Archive", need: author,
      bad: selectedRows.filter((p) => p.published || p.status === "ARCHIVED"),
      why: "published or already archived",
    },
    {
      id: "stop", label: "Stop in every branch", need: may("OFFERING_MANAGE"),
      bad: selectedRows.filter((p) => inMenuCount(p) === 0),
      why: "not offered in any branch",
    },
    { id: "export", label: "Export to Excel", bad: [], need: true },
  ];

  const product = PRODUCTS.find((p) => p.id === productId) || null;

  return (
    <div style={{ display: "flex", gap: 16, alignItems: "flex-start", minWidth: 0 }}>
      <CategoryRail cats={cats} setCats={setCats} />

      <div style={{ flex: 1, minWidth: 0 }}>
        {/* Primary axis: work queues sit here with the statuses, because "no
            ИКПУ" and "no photo" are what an author opens this screen to clear. */}
        <div style={{ display: "flex", flexWrap: "wrap", borderBottom: `1px solid ${hairline}`, marginBottom: 0 }}>
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => { setTab(t.id); setSelected([]); }}
              className="q-body-sm"
              style={{
                padding: "10px 14px", background: "transparent", border: "none", marginBottom: -1,
                borderBottom: t.id === tab ? `2px solid ${blue}` : "2px solid transparent",
                color: t.id === tab ? ink : inkMuted, cursor: "pointer", whiteSpace: "nowrap",
              }}
            >
              {t.label}
              <span className="q-tnum" style={{ color: inkSubtle, marginLeft: 6 }}>{t.n}</span>
            </button>
          ))}
        </div>

        <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", padding: 12, background: canvas, border: `1px solid ${hairline}`, borderTop: "none" }}>
          <SearchInput value={query} onChange={setQuery} placeholder="Name, code or SKU" />
          <Select
            label="Catalog" value="cat-main" onChange={() => {}}
            options={[{ value: "cat-main", label: "Asosiy menyu" }, { value: "cat-season", label: "Yozgi menyu" }]}
          />
          <Select
            label="Sort" value={sort} onChange={setSort}
            options={[
              { value: "state", label: "By state" },
              { value: "name", label: "Name" },
              { value: "price", label: "Price" },
            ]}
          />
          <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
            {author ? <Button size="sm" variant="tertiary" onClick={() => {}}>Import from Excel</Button> : null}
            {author ? <Button size="sm" onClick={() => {}}>New product</Button> : null}
          </div>
        </div>

        {selected.length ? (
          <div style={{ background: "var(--q-info-tint)", borderLeft: `3px solid ${blue}`, padding: "10px 12px", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
            <span className="q-emphasis" style={{ color: "var(--q-info-text)" }}>
              {selected.length} selected
            </span>
            {bulk.filter((b) => b.need).map((b) => (
              <Button
                key={b.id} size="sm" variant={b.bad.length ? "ghost" : "tertiary"}
                disabled={b.bad.length > 0}
                onClick={() => b.id === "archive" ? setConfirming({ kind: "bulk-archive" }) : undefined}
              >
                {b.label}
              </Button>
            ))}
            <Button size="sm" variant="ghost" onClick={() => setSelected([])}>Clear</Button>
            <div style={{ width: "100%" }}>
              {bulk.filter((b) => b.need && b.bad.length).map((b) => (
                <div key={b.id} className="q-caption" style={{ color: "var(--q-info-text)" }}>
                  {b.label} unavailable: {b.bad.length} of {selected.length} selected are {b.why}.
                </div>
              ))}
            </div>
          </div>
        ) : null}

        <div style={{ border: `1px solid ${hairline}`, borderTop: selected.length ? `1px solid ${hairline}` : "none", background: canvas, overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 1080 }}>
            <thead>
              <tr>
                <Th width={36}>
                  <input
                    type="checkbox"
                    checked={rows.length > 0 && selected.length === rows.length}
                    onChange={(e) => setSelected(e.target.checked ? rows.map((p) => p.id) : [])}
                    aria-label="Select the filtered set"
                  />
                </Th>
                <Th width={44}>Photo</Th>
                <Th>Name</Th>
                <Th width={96}>Code</Th>
                <Th width={140}>Category</Th>
                <Th align="right" width={72}>Variants</Th>
                <Th align="right" width={160}>Price</Th>
                <Th width={150}>ИКПУ</Th>
                <Th width={100}>Status</Th>
                <Th width={110}>In menu</Th>
                <Th width={120}>Published</Th>
                <Th width={36} />
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <ProductRow
                  key={p.id} p={p}
                  checked={selected.includes(p.id)} onCheck={() => toggle(p.id)}
                  onOpen={() => setProductId(p.id)}
                  onMenu={() => setMenuFor(menuFor === p.id ? null : p.id)}
                  menuOpen={menuFor === p.id}
                  author={author}
                  onAction={(kind) => { setMenuFor(null); setConfirming({ kind, id: p.id }); }}
                  toMenus={() => setView("menus")}
                />
              ))}
              {!rows.length ? (
                <tr>
                  <td colSpan={12} style={{ ...cell, padding: 0 }}>
                    <EmptyState
                      title={query || cats.length ? "No products match the filter" : "This catalog has no products yet"}
                      action={
                        <Button variant="tertiary" onClick={() => { setQuery(""); setCats([]); setTab("all"); }}>
                          Reset filters
                        </Button>
                      }
                    />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>

        <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
          Sorted by state: blockers, then missing ИКПУ or photo, then drafts, then active — alphabetical within each band.
          Keyboard (j / k / Enter / Space / e / s) is specified in 4.1 and not prototyped here.
        </div>
      </div>

      {product ? (
        <ProductDrawer p={product} onClose={() => setProductId(null)} offeringAt={offeringAt} author={author} setView={setView} />
      ) : null}

      {confirming?.kind === "archive" ? (() => {
        const p = PRODUCTS.find((x) => x.id === confirming.id);
        return (
          <Confirm
            title="Archive product" danger confirmLabel="Archive"
            onClose={() => setConfirming(null)} onConfirm={() => setConfirming(null)}
            body={`«${p.name}» and its ${activeVariants(p).length} variant(s) will be archived. ${p.published ? "It is in the live publication — customers keep seeing it until the menu is published again." : "It is not in the live publication."}`}
          >
            <div className="q-caption" style={{ color: inkMuted }}>
              Archive, never delete. pricing.quote_lines.source_variant_id and every order snapshot
              reference these rows, so a hard delete would make history unanswerable.
            </div>
          </Confirm>
        );
      })() : null}

      {confirming?.kind === "stop-all" ? (() => {
        const p = PRODUCTS.find((x) => x.id === confirming.id);
        return (
          <Confirm
            title="Stop in every branch" danger confirmLabel={`Stop in ${inMenuCount(p)} branches`}
            onClose={() => setConfirming(null)} onConfirm={() => setConfirming(null)}
            body={`«${p.name}» will be set unavailable in ${inMenuCount(p)} branches, with an AVAILABILITY_CHANGE movement per stock item.`}
          />
        );
      })() : null}

      {confirming?.kind === "bulk-archive" ? (
        <Confirm
          title="Archive selection" danger confirmLabel={`Archive ${selected.length}`}
          onClose={() => setConfirming(null)} onConfirm={() => { setConfirming(null); setSelected([]); }}
          body={`${selected.length} products will be archived. None of them is in the live publication.`}
        />
      ) : null}
    </div>
  );
}

function ProductRow({ p, checked, onCheck, onOpen, onMenu, menuOpen, author, onAction, toMenus }) {
  const sev = severityOf(p);
  const s = SEV[sev.level];
  const range = priceRange(p);
  const nVar = activeVariants(p).length;
  const inMenu = inMenuCount(p);
  const draft = p.status === "DRAFT";
  const archived = p.status === "ARCHIVED";

  return (
    <tr
      onClick={onOpen}
      style={{ cursor: "pointer", background: s.tint }}
      onMouseEnter={(e) => { if (sev.level === "none") e.currentTarget.style.background = surface1; }}
      onMouseLeave={(e) => { if (sev.level === "none") e.currentTarget.style.background = "transparent"; }}
    >
      <td style={{ ...cell, borderLeft: `3px solid ${s.rule}`, paddingLeft: 9 }} onClick={(e) => e.stopPropagation()}>
        <input type="checkbox" checked={checked} onChange={onCheck} aria-label={p.name} />
      </td>
      <td style={cell}><Thumb status={p.photo} /></td>
      <td style={{ ...cell, maxWidth: 320 }}>
        <div className="q-emphasis" style={{ color: archived || draft ? inkMuted : ink }}>{p.name}</div>
        <div className="q-caption" style={{ color: inkSubtle }}>{p.nameRu}</div>
        {/* Strict precedence: a blocker suppresses the warning caption. A bare
            badge would not tell the author what to fix. */}
        {sev.reason ? (
          <div className="q-caption" style={{ color: s.text, marginTop: 2 }}>{sev.reason}</div>
        ) : null}
      </td>
      <td style={{ ...cell, ...MONO, color: inkMuted }} className="q-body-sm">{p.code}</td>
      <td style={cell}>
        <Chip>{CAT[p.categoryIds[0]].name}</Chip>
      </td>
      <td style={{ ...cell, textAlign: "right" }} className="q-body-sm q-tnum">
        {nVar > 1 ? <Link onClick={onOpen}>{nVar}</Link> : <span style={{ color: inkSubtle }}>—</span>}
      </td>
      <td style={{ ...cell, textAlign: "right", color: ink }} className="q-body-sm q-tnum">
        {!range ? <span style={{ color: "var(--q-error-text)" }}>No price</span>
          : range.min === range.max ? uzs(range.min)
          : `${uzs(range.min).replace(" so'm", "")} – ${uzs(range.max)}`}
      </td>
      <td style={cell} className="q-body-sm">
        {p.mxik ? <span style={{ ...MONO, color: inkMuted }}>{p.mxik}</span> : <Chip tone="warn">No ИКПУ</Chip>}
      </td>
      <td style={cell}>
        <StatusPill tone={archived ? "neutral" : draft ? "pending" : "active"}>
          {archived ? "Archive" : draft ? "Draft" : "Active"}
        </StatusPill>
      </td>
      <td style={cell} className="q-body-sm">
        {inMenu === 0
          ? <Chip>Not in any menu</Chip>
          : <Link onClick={toMenus}>{inMenu}/{LOCATIONS.length} branches</Link>}
      </td>
      <td style={cell} className="q-body-sm">
        {p.published
          ? <span style={{ color: inkMuted }}>Published</span>
          : <Chip tone="warn">Not published</Chip>}
      </td>
      <td style={{ ...cell, position: "relative" }} onClick={(e) => e.stopPropagation()}>
        <button
          type="button" onClick={onMenu} aria-label="Row actions" className="q-body-sm"
          style={{ background: "none", border: "none", color: inkMuted, cursor: "pointer", padding: 4 }}
        >
          ⋯
        </button>
        {menuOpen ? (
          <div style={{ position: "absolute", right: 8, top: 32, zIndex: 20, background: canvas, border: `1px solid ${hairline}`, minWidth: 240 }}>
            {[
              { label: "Edit", off: !author || archived },
              { label: "Duplicate", off: !author },
              { label: "Copy id", off: false },
              { label: "Add to category…", off: !author },
              { label: "Set ИКПУ…", off: !author },
              { label: "Stop in every branch", off: inMenuCount(p) === 0, act: "stop-all" },
              { label: "Archive", off: !author || archived, act: "archive" },
            ].map((a) => (
              <button
                key={a.label} type="button" disabled={a.off}
                onClick={() => a.act && onAction(a.act)}
                className="q-body-sm"
                style={{
                  display: "block", width: "100%", textAlign: "left", padding: "8px 12px",
                  background: "none", border: "none", color: a.off ? inkSubtle : ink,
                  cursor: a.off ? "not-allowed" : "pointer",
                }}
              >
                {a.label}
              </button>
            ))}
            <div className="q-caption" style={{ padding: "8px 12px", color: inkSubtle, borderTop: `1px solid ${hairline}` }}>
              Delete is refused. Archive stands in its place, in the same position.
            </div>
          </div>
        ) : null}
      </td>
    </tr>
  );
}

function CategoryRail({ cats, setCats }) {
  const own = (id) => PRODUCTS.filter((p) => p.categoryIds.includes(id) && p.status !== "ARCHIVED").length;
  const count = (id) =>
    own(id) + CAT_CATEGORIES.filter((c) => c.parentId === id).reduce((n, c) => n + count(c.id), 0);
  const roots = CAT_CATEGORIES.filter((c) => !c.parentId);
  const subtree = (id) => [id, ...CAT_CATEGORIES.filter((c) => c.parentId === id).flatMap((c) => subtree(c.id))];
  const toggle = (id) => setCats((s) => {
    const ids = subtree(id);
    return s.includes(id) ? s.filter((x) => !ids.includes(x)) : [...new Set([...s, ...ids])];
  });

  const node = (c, depth) => {
    const n = count(c.id);
    const on = cats.includes(c.id);
    return (
      <div key={c.id}>
        <button
          type="button" onClick={() => toggle(c.id)} className="q-body-sm"
          style={{
            display: "flex", width: "100%", gap: 8, alignItems: "baseline",
            padding: `6px 12px 6px ${12 + depth * 14}px`, background: on ? "var(--q-info-tint)" : "transparent",
            border: "none", borderLeft: `3px solid ${on ? blue : "transparent"}`,
            color: n === 0 ? inkSubtle : ink, cursor: "pointer", textAlign: "left",
          }}
        >
          <span style={{ minWidth: 0, flex: 1 }}>{c.name}</span>
          <span className="q-caption q-tnum" style={{ color: inkSubtle }}>{n || "Empty"}</span>
        </button>
        {CAT_CATEGORIES.filter((x) => x.parentId === c.id).map((x) => node(x, depth + 1))}
      </div>
    );
  };

  return (
    <div style={{ width: 220, flexShrink: 0, border: `1px solid ${hairline}`, background: canvas }}>
      <div className="q-caption" style={{ padding: "10px 12px", background: surface1, color: inkMuted, fontWeight: 600, borderBottom: `1px solid ${hairline}` }}>
        Categories
      </div>
      {roots.map((c) => node(c, 0))}
      <div className="q-caption" style={{ padding: "8px 12px", color: inkSubtle, borderTop: `1px solid ${hairline}` }}>
        Tree order is the customer's order (4.3). Drag reordering is not prototyped.
      </div>
    </div>
  );
}

/* The drawer opens at 640px with a link promoting it to the full editor for
 * real work. 4.2 itself is not prototyped; the drawer says so rather than
 * offering a link that goes nowhere. */
function ProductDrawer({ p, onClose, offeringAt, author, setView }) {
  const blockers = blockersFor(p);
  const warnings = warningsFor(p);
  return (
    <Drawer title={p.name} onClose={onClose} width={640}>
      <div style={{ display: "flex", gap: 16, alignItems: "flex-start" }}>
        <Thumb status={p.photo} size={72} />
        <div style={{ minWidth: 0 }}>
          <div className="q-body-sm" style={{ color: inkMuted }}>{p.nameRu}</div>
          <div className="q-caption" style={{ ...MONO, color: inkSubtle, marginTop: 4 }}>{p.code}</div>
          <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>Updated {dt(p.updatedAt)}</div>
        </div>
        <div style={{ marginLeft: "auto" }}>
          <StatusPill tone={p.status === "ARCHIVED" ? "neutral" : p.status === "DRAFT" ? "pending" : "active"}>
            {p.status === "ARCHIVED" ? "Archive" : p.status === "DRAFT" ? "Draft" : "Active"}
          </StatusPill>
        </div>
      </div>

      {p.published ? (
        <div style={{ marginTop: 16 }}>
          <Banner tone="info" title="This product is published">
            Customers see edits only after the menu is published again. Availability is the exception —
            it writes location_offerings directly and takes effect at once.
          </Banner>
        </div>
      ) : null}

      {/* The readiness rail from 4.2, condensed. Validation while editing, not a
          report at publication time — the highest-value divergence from Delever. */}
      <div style={{ marginTop: 16, border: `1px solid ${hairline}` }}>
        <div className="q-caption" style={{ padding: "8px 12px", background: surface1, color: inkMuted, fontWeight: 600 }}>
          Readiness
        </div>
        {!blockers.length && !warnings.length ? (
          <div className="q-body-sm" style={{ padding: 12, color: inkMuted }}>No findings. This product can be published.</div>
        ) : null}
        {blockers.map((c) => (
          <div key={c} className="q-body-sm" style={{ padding: "8px 12px", borderLeft: "3px solid var(--q-error)", background: "var(--q-error-tint)", color: "var(--q-error-text)" }}>
            {FINDING_TEXT[c]}
            <span className="q-caption" style={{ ...MONO, marginLeft: 8 }}>{c}</span>
            {c === "MEDIA_NOT_AVAILABLE" && p.photoRejection ? (
              <div className="q-caption" style={{ marginTop: 2 }}>
                {p.photoRejection.code} — {p.photoRejection.detail}
              </div>
            ) : null}
          </div>
        ))}
        {warnings.map((c) => (
          <div key={c} className="q-body-sm" style={{ padding: "8px 12px", borderLeft: "3px solid var(--q-warning)", background: "var(--q-warning-tint)", color: "var(--q-warning-text)" }}>
            {FINDING_TEXT[c]}
            <span className="q-caption" style={{ ...MONO, marginLeft: 8 }}>{c}</span>
          </div>
        ))}
      </div>

      <div className="q-emphasis" style={{ color: ink, marginTop: 24, marginBottom: 8 }}>Variants</div>
      <table style={{ width: "100%", borderCollapse: "collapse", border: `1px solid ${hairline}` }}>
        <thead>
          <tr>
            <Th>Variant</Th><Th width={110}>SKU</Th><Th width={60}>Default</Th>
            <Th align="right" width={110}>Price</Th><Th width={90}>Status</Th>
          </tr>
        </thead>
        <tbody>
          {p.variants.map((v) => (
            <tr key={v.id}>
              <td style={cell} className="q-body-sm">{v.name || p.name}</td>
              <td style={{ ...cell, ...MONO, color: inkMuted }} className="q-body-sm">{v.sku}</td>
              <td style={cell} className="q-body-sm">{v.isDefault ? "Yes" : "—"}</td>
              <td
                style={{ ...cell, textAlign: "right", background: v.priceMinor == null ? "var(--q-error-tint)" : undefined, color: v.priceMinor == null ? "var(--q-error-text)" : ink }}
                className="q-body-sm q-tnum"
              >
                {v.priceMinor == null ? "No active price" : uzs(v.priceMinor)}
              </td>
              <td style={cell} className="q-body-sm" >{v.status === "ARCHIVED" ? "Archived" : "Active"}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* 4.2 tab 6, read-mostly. The writes live in the matrix and the stop
          list; what an author needs here is who changed it and why. */}
      <div className="q-emphasis" style={{ color: ink, marginTop: 24, marginBottom: 8 }}>Availability by branch</div>
      <div style={{ border: `1px solid ${hairline}` }}>
        {LOCATIONS.map((l) => {
          const v = activeVariants(p)[0];
          const st = v ? offeringAt(v, l.id) : null;
          const mv = v ? MOVEMENTS[`${v.id}@${l.id}`] : null;
          return (
            <div key={l.id} style={{ display: "flex", gap: 12, alignItems: "baseline", padding: "8px 12px", borderBottom: `1px solid ${hairline}` }}>
              <span className="q-body-sm" style={{ color: ink, minWidth: 150 }}>{l.name}</span>
              {l.mode === "FORCE_CLOSED" ? <Chip tone="warn">Closed</Chip> : null}
              <span className="q-body-sm" style={{ color: st === "UNAVAILABLE" ? "var(--q-warning-text)" : inkMuted }}>
                {st === "AVAILABLE" ? "In menu" : st === "UNAVAILABLE" ? "On stop" : st === "HIDDEN" ? "Hidden" : "Not listed"}
              </span>
              <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto", textAlign: "right" }}>
                {mv ? `${dt(mv.at)} · ${ACTORS[mv.actorId].name} · ${REASON_LABEL[mv.reason] || "—"}` : "—"}
              </span>
            </div>
          );
        })}
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 24, flexWrap: "wrap" }}>
        {author ? <Button variant="tertiary" disabled>Open full editor</Button> : null}
        <Button variant="ghost" onClick={() => setView("menus")}>Show in the branch matrix</Button>
      </div>
      <div style={{ marginTop: 12 }}>
        <NotBuilt>the full product editor (4.2, seven tabs) is specified and not prototyped here. {NOT_BUILT.fiscal}</NotBuilt>
      </div>
    </Drawer>
  );
}

/* ── 4.5 Menus — layer A, the offering matrix ──────────────────────────────
 * "What does this branch actually sell?" A frozen-first-column matrix, because
 * the question is comparative — which branches are missing this — and a list
 * forces the operator to hold four branches in their head.
 */
function MenusView({ may, offeringAt, writeOffering }) {
  const [axis, setAxis] = useState("locations");
  const [bySeverity, setBySeverity] = useState(false);
  const [collapsed, setCollapsed] = useState([]);
  const canManage = may("OFFERING_MANAGE");

  const groups = useMemo(() => {
    const out = [];
    for (const c of CAT_CATEGORIES) {
      const items = [];
      for (const p of PRODUCTS) {
        if (p.status === "ARCHIVED" || !p.categoryIds.includes(c.id)) continue;
        for (const v of activeVariants(p)) items.push({ p, v });
      }
      if (items.length) out.push({ cat: c, items });
    }
    return out;
  }, []);

  const severityOfRow = ({ p, v }) => {
    if (v.priceMinor == null) return 0;
    if (LOCATIONS.every((l) => offeringAt(v, l.id) === null)) return 1;
    if (LOCATIONS.some((l) => offeringAt(v, l.id) === "UNAVAILABLE")) return 2;
    return 3;
  };

  const cycle = (v, loc) => {
    if (!canManage) return;
    const cur = offeringAt(v, loc);
    const next = cur === null ? "AVAILABLE" : cur === "AVAILABLE" ? "UNAVAILABLE" : cur === "UNAVAILABLE" ? "HIDDEN" : "AVAILABLE";
    writeOffering(v, loc, next);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0 }}>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap", padding: 12, background: canvas, border: `1px solid ${hairline}` }}>
        <div style={{ display: "flex", border: `1px solid ${hairline}` }}>
          {["locations", "channels"].map((a) => (
            <button
              key={a} type="button" onClick={() => setAxis(a)} className="q-body-sm"
              style={{
                padding: "6px 12px", border: "none", cursor: "pointer",
                background: axis === a ? blue : "transparent", color: axis === a ? "#fff" : inkMuted,
              }}
            >
              {a === "locations" ? "Branches" : "Channels"}
            </button>
          ))}
        </div>
        <Button size="sm" variant={bySeverity ? "secondary" : "tertiary"} onClick={() => setBySeverity(!bySeverity)}>
          {bySeverity ? "Sorted by problem" : "Sort by problem"}
        </Button>
        <div style={{ marginLeft: "auto", display: "flex", gap: 8 }}>
          <Button size="sm" variant="ghost">Export menu to Excel</Button>
        </div>
      </div>

      {axis === "channels" ? (
        <ChannelPlane />
      ) : (
        <>
          <div style={{ display: "flex", gap: 16, flexWrap: "wrap" }}>
            <span className="q-caption" style={{ color: inkMuted }}>
              In menu, on stop and hidden are three different facts. Hidden is not shown to the customer at all;
              on stop is shown as sold out. A dashed cell is an item with no offering row — never listed, which is
              different again.
            </span>
          </div>

          <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
            <table style={{ borderCollapse: "collapse", minWidth: 900, width: "100%" }}>
              <thead>
                <tr>
                  <Th width={280}>Product · variant</Th>
                  <Th align="right" width={130}>Price</Th>
                  {LOCATIONS.map((l) => (
                    <Th key={l.id} width={150}>
                      <div>{l.name}</div>
                      {l.mode === "FORCE_CLOSED" ? (
                        <div style={{ marginTop: 4, fontWeight: 400 }}>
                          <Chip tone="warn">Closed until {l.closedUntil.slice(11, 16)} · {l.closedReason}</Chip>
                        </div>
                      ) : null}
                    </Th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {groups.map((g) => {
                  const open = !collapsed.includes(g.cat.id);
                  const items = bySeverity ? [...g.items].sort((a, b) => severityOfRow(a) - severityOfRow(b)) : g.items;
                  return (
                    <Fragment key={g.cat.id}>
                      <tr>
                        <td colSpan={2 + LOCATIONS.length} style={{ ...cell, background: surface1, padding: 0 }}>
                          <button
                            type="button"
                            onClick={() => setCollapsed((s) => (open ? [...s, g.cat.id] : s.filter((x) => x !== g.cat.id)))}
                            className="q-emphasis"
                            style={{ display: "block", width: "100%", textAlign: "left", padding: "8px 12px", background: "none", border: "none", color: ink, cursor: "pointer" }}
                          >
                            {open ? "−" : "+"} {categoryPath(g.cat.id)}
                            <span className="q-caption q-tnum" style={{ color: inkSubtle, marginLeft: 8 }}>{g.items.length}</span>
                          </button>
                        </td>
                      </tr>
                      {open ? items.map(({ p, v }) => (
                        <tr key={v.id}>
                          <td style={{ ...cell, maxWidth: 280, borderLeft: `3px solid ${v.priceMinor == null ? "var(--q-error)" : "transparent"}`, paddingLeft: 9, background: v.priceMinor == null ? "var(--q-error-tint)" : undefined }}>
                            <div className="q-body-sm" style={{ color: ink }}>
                              {p.name}{v.name ? ` · ${v.name}` : ""}
                            </div>
                            <div className="q-caption" style={{ ...MONO, color: inkSubtle }}>{v.sku}</div>
                          </td>
                          <td style={{ ...cell, textAlign: "right" }} className="q-body-sm q-tnum">
                            {v.priceMinor == null
                              ? <span style={{ color: "var(--q-error-text)" }}>No price</span>
                              : uzs(v.priceMinor)}
                          </td>
                          {LOCATIONS.map((l) => (
                            <MatrixCell
                              key={l.id} status={offeringAt(v, l.id)} readOnly={!canManage}
                              modes={modesFor(v)} onClick={() => cycle(v, l.id)}
                            />
                          ))}
                        </tr>
                      )) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="q-caption" style={{ color: inkSubtle }}>
            Cell click cycles in menu → on stop → hidden, optimistic and immediate; it writes
            catalog.location_offerings, not a draft. Keyboard cell cursor, fill-down and column
            selection are specified in 4.5 and not prototyped.
          </div>
          <NotBuilt>{NOT_BUILT.menuEntity} Copy menu to another branch waits on it — it is not faked with 400 individual writes.</NotBuilt>
        </>
      )}
    </div>
  );
}

function MatrixCell({ status, onClick, readOnly, modes }) {
  const map = {
    AVAILABLE: { label: "In menu", bg: canvas, fg: ink, border: hairline, rule: "transparent" },
    UNAVAILABLE: { label: "On stop", bg: "var(--q-warning-tint)", fg: "var(--q-warning-text)", border: "var(--q-warning)", rule: "var(--q-warning)" },
    HIDDEN: { label: "Hidden", bg: surface1, fg: inkSubtle, border: hairline, rule: "transparent" },
  };
  const s = status ? map[status] : null;
  return (
    <td style={{ ...cell, padding: 6 }}>
      <button
        type="button" onClick={onClick} disabled={readOnly} className="q-caption"
        style={{
          width: "100%", minHeight: 40, padding: "6px 8px", textAlign: "left",
          background: s ? s.bg : "transparent",
          border: s ? `1px solid ${s.border}` : `1px dashed ${inkSubtle}`,
          borderLeft: s ? `3px solid ${s.rule}` : `1px dashed ${inkSubtle}`,
          color: s ? s.fg : inkSubtle, cursor: readOnly ? "default" : "pointer",
        }}
      >
        <div>{s ? s.label : "Not listed"}</div>
        {s ? (
          <div style={{ color: modes.length < MODES_DEFAULT.length ? "var(--q-warning-text)" : inkSubtle, marginTop: 2 }}>
            {modes.map((m) => MODE_SHORT[m]).join(" · ")}
          </div>
        ) : null}
      </button>
    </td>
  );
}

/* Layer B. The channel axis needs a table V0020 did not build, so the screen
 * says which half exists and which does not rather than showing a toggle that
 * writes nowhere. */
function ChannelPlane() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <NotBuilt>{NOT_BUILT.channelPlane} Availability per channel and price per channel are separate facts and must stay separate — a price of zero means free, never "not sold".</NotBuilt>
      <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
        {CHANNELS.map((c) => {
          const archived = c.status === "ARCHIVED";
          return (
            <div key={c.id} style={{ display: "flex", gap: 12, alignItems: "baseline", flexWrap: "wrap", padding: "10px 12px", borderBottom: `1px solid ${hairline}`, background: archived ? surface1 : undefined }}>
              <span className="q-body-sm" style={{ color: archived ? inkSubtle : ink, minWidth: 140 }}>{c.name}</span>
              <span className="q-caption" style={{ ...MONO, color: inkSubtle, minWidth: 110 }}>{c.systemType}</span>
              {archived ? <Chip>Archived</Chip>
                : c.externallyPriced ? <Chip tone="info">Priced externally</Chip>
                : c.pricePlane ? <span className="q-caption" style={{ color: inkMuted }}>Takes hall prices</span>
                : <span className="q-caption" style={{ color: inkMuted }}>Brand price book</span>}
              <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
                offered_on_channel — ADR 0036
              </span>
            </div>
          );
        })}
      </div>
      <div className="q-caption" style={{ color: inkSubtle }}>
        Price per channel resolves today through pricing.price_book_assignments at CHANNEL scope,
        honouring price_plane_channel_id — which is how QR and kiosk take hall prices without a
        global switch. Only item enablement is missing, and an aggregator that prices on its own
        side renders read-only rather than offering a control that is a lie.
      </div>
    </div>
  );
}

/* ── 4.6 Availability and the stop list ────────────────────────────────────
 * "Osh tugadi" — one tap, from a phone, in the kitchen, on every channel at
 * once. The whole view is designed backwards from that gesture: 56px rows, one
 * action per row, no drawer, no modal, no confirm, no save.
 *
 * No confirmation dialog on a single stop. The action is instantly reversible,
 * it is recorded with an actor, and the cost of a mis-tap — one dish briefly
 * unavailable — is smaller than the cost of a confirm step during a rush.
 * Confirmation is required only for the bulk variant, and there a reason is
 * required too: optional on one tap keeps the gesture, mandatory on forty rows
 * keeps the evidence.
 */
function AvailabilityView({ may, locationId, setLocationId, offeringAt, movementAt, stop, resume, setView, reachable }) {
  const [tab, setTab] = useState("available");
  const [query, setQuery] = useState("");
  const [explainFor, setExplainFor] = useState(null);
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState([]);
  const [bulkConfirm, setBulkConfirm] = useState(null);
  const searchRef = useRef(null);
  const canAdjust = may("INVENTORY_ADJUST") && may("OFFERING_MANAGE");
  const loc = LOC[locationId];

  useEffect(() => { searchRef.current?.querySelector("input")?.focus(); }, []);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return variantRowsAt(locationId)
      .map((r) => {
        const offering = offeringAt(r.variant, locationId);
        return { ...r, offering, stopped: offering === "UNAVAILABLE", movement: movementAt(r.variant, locationId) };
      })
      .filter((r) => r.offering !== "HIDDEN")
      .filter((r) => (tab === "stopped" ? r.stopped : !r.stopped))
      .filter((r) => !q || `${r.product.name} ${r.product.nameRu} ${r.variant.sku}`.toLowerCase().includes(q));
  }, [locationId, tab, query, offeringAt, movementAt]);

  const counts = useMemo(() => {
    const all = variantRowsAt(locationId).map((r) => offeringAt(r.variant, locationId));
    return {
      available: all.filter((s) => s === "AVAILABLE").length,
      stopped: all.filter((s) => s === "UNAVAILABLE").length,
    };
  }, [locationId, offeringAt]);

  /* Sort. Severity first, oldest first inside the band — a stop set before the
   * shift started is revenue nobody has noticed going missing, and it outranks
   * the one set two minutes ago. Within the recent band the spec's own rule
   * holds: most recently stopped first, because the operator's next action is
   * usually to return the thing they just stopped.
   * Available sorts by category then menu order, because the operator is
   * scanning for a dish they can picture in the menu's shape. */
  const sorted = useMemo(() => {
    if (tab !== "stopped") {
      return [...rows].sort((a, b) =>
        menuOrder(a.categoryId) - menuOrder(b.categoryId) ||
        a.product.name.localeCompare(b.product.name));
    }
    const age = (r) => (r.movement ? minutesBetween(r.movement.at, NOW) : 0);
    return [...rows].sort((a, b) => {
      const band = (r) => (age(r) >= 120 ? 0 : 1);
      return band(a) - band(b) || (band(a) === 0 ? age(b) - age(a) : age(a) - age(b));
    });
  }, [rows, tab]);

  const stale = sorted.filter((r) => r.movement && minutesBetween(r.movement.at, NOW) >= 120);

  const toggleSel = (key) => setSelected((s) => (s.includes(key) ? s.filter((x) => x !== key) : [...s, key]));

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12, minWidth: 0, maxWidth: 900 }}>
      {loc.mode === "FORCE_CLOSED" ? (
        <Banner tone="error" title={`${loc.name} is force closed until ${loc.closedUntil.slice(11, 16)}`}>
          {loc.closedReason}. Nothing here is selling while the branch is closed, whatever its stop state says.
        </Banner>
      ) : null}

      <div style={{ display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", padding: 12, background: canvas, border: `1px solid ${hairline}` }}>
        <div ref={searchRef} style={{ flex: 1, minWidth: 240 }}>
          <SearchInput value={query} onChange={setQuery} placeholder="Find a dish" />
        </div>
        {LOCATIONS.length > 1 ? (
          <Select value={locationId} onChange={setLocationId} options={LOCATIONS.map((l) => ({ value: l.id, label: l.name }))} />
        ) : null}
        {canAdjust ? (
          <Button size="sm" variant={selecting ? "secondary" : "tertiary"} onClick={() => { setSelecting(!selecting); setSelected([]); }}>
            {selecting ? "Done" : "Select"}
          </Button>
        ) : null}
      </div>

      <div style={{ display: "flex", borderBottom: `1px solid ${hairline}` }}>
        {[
          { id: "available", label: "Available", n: counts.available },
          { id: "stopped", label: "On stop", n: counts.stopped },
        ].map((t) => (
          <button
            key={t.id} type="button" onClick={() => { setTab(t.id); setSelected([]); }} className="q-body"
            style={{
              padding: "12px 20px", background: "transparent", border: "none", marginBottom: -1,
              borderBottom: t.id === tab ? `2px solid ${blue}` : "2px solid transparent",
              color: t.id === tab ? ink : inkMuted, cursor: "pointer",
            }}
          >
            {t.label}
            <span className="q-tnum" style={{ color: inkSubtle, marginLeft: 8 }}>{t.n}</span>
          </button>
        ))}
      </div>

      {tab === "stopped" && stale.length ? (
        <Banner tone="warn" title={`${stale.length} dishes have been on stop for over two hours`}>
          Sorted first, oldest first. A dish left off after the delivery arrived is revenue nobody notices going missing.
        </Banner>
      ) : null}

      {selecting && selected.length ? (
        <div style={{ background: "var(--q-info-tint)", borderLeft: `3px solid ${blue}`, padding: "10px 12px", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap" }}>
          <span className="q-emphasis" style={{ color: "var(--q-info-text)" }}>{selected.length} selected</span>
          <Button size="sm" onClick={() => setBulkConfirm({ mode: tab === "stopped" ? "resume" : "stop", reason: "OUT_OF_STOCK" })}>
            {tab === "stopped" ? `Remove from stop (${selected.length})` : `Add to stop (${selected.length})`}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected([])}>Clear</Button>
        </div>
      ) : null}

      <div style={{ border: `1px solid ${hairline}`, background: canvas }}>
        {sorted.map((r) => (
          <StopRow
            key={r.key} r={r} selecting={selecting} selected={selected.includes(r.key)}
            onSelect={() => toggleSel(r.key)}
            canAdjust={canAdjust}
            onStop={() => stop(r.variant, locationId, r.product.name)}
            onResume={() => resume(r.variant, locationId, r.product.name)}
            onExplain={() => setExplainFor(r.key)}
          />
        ))}
        {!sorted.length ? (
          <div style={{ padding: 32, textAlign: "center" }}>
            <div className="q-body" style={{ color: ink }}>
              {tab === "stopped" ? "Nothing is on stop" : "No dish matches"}
            </div>
            <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
              {tab === "stopped" ? "A good state, not an error." : "Clear the search to see the full list."}
            </div>
          </div>
        ) : null}
      </div>

      {!canAdjust ? (
        <div className="q-caption" style={{ color: inkSubtle }}>
          This actor holds inventory.read without inventory.adjust, so the stop buttons are absent rather than disabled.
        </div>
      ) : (
        <div className="q-caption" style={{ color: inkSubtle }}>
          One tap writes the availability and records an AVAILABILITY_CHANGE movement with its actor and idempotency
          key. A double tap is harmless — the service is a no-op when the state already matches. The reason is asked
          for after the dish is already off, and ignoring it writes nothing further.
        </div>
      )}
      <NotBuilt>{NOT_BUILT.countedStock} {NOT_BUILT.stopSource}</NotBuilt>

      {explainFor ? (
        <ExplainerDrawer
          row={sorted.find((r) => r.key === explainFor) || rows.find((r) => r.key === explainFor)}
          onClose={() => setExplainFor(null)} setView={setView} reachable={reachable}
        />
      ) : null}

      {bulkConfirm ? (
        <Confirm
          title={bulkConfirm.mode === "stop" ? "Add to stop" : "Remove from stop"}
          confirmLabel={bulkConfirm.mode === "stop" ? `Stop ${selected.length}` : `Return ${selected.length}`}
          danger={bulkConfirm.mode === "stop"}
          body={`${selected.length} dishes at ${loc.name}. Each write records a movement with the reason below.`}
          onClose={() => setBulkConfirm(null)}
          onConfirm={() => {
            for (const key of selected) {
              const r = rows.find((x) => x.key === key);
              if (!r) continue;
              if (bulkConfirm.mode === "stop") stop(r.variant, locationId, r.product.name, bulkConfirm.reason);
              else resume(r.variant, locationId, r.product.name);
            }
            setSelected([]); setBulkConfirm(null);
          }}
        >
          {bulkConfirm.mode === "stop" ? (
            <div>
              <div className="q-caption" style={{ color: inkMuted, marginBottom: 8 }}>
                A reason is optional on one tap and required here — optional on forty rows would destroy the evidence.
              </div>
              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                {STOP_REASONS.map((rr) => (
                  <button
                    key={rr.code} type="button"
                    onClick={() => setBulkConfirm({ ...bulkConfirm, reason: rr.code })}
                    className="q-body-sm"
                    style={{
                      padding: "6px 10px", cursor: "pointer",
                      background: bulkConfirm.reason === rr.code ? "var(--q-info-tint)" : canvas,
                      border: `1px solid ${bulkConfirm.reason === rr.code ? blue : hairline}`,
                      color: bulkConfirm.reason === rr.code ? "var(--q-info-text)" : ink,
                    }}
                  >
                    {rr.label}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
        </Confirm>
      ) : null}
    </div>
  );
}

/* 56px rows, 44px minimum action target, one action per row. Touch first; the
 * desktop rendering is the same list at the same size. */
function StopRow({ r, selecting, selected, onSelect, canAdjust, onStop, onResume, onExplain }) {
  const mv = r.movement;
  const old = mv && minutesBetween(mv.at, NOW) >= 120;
  const sev = r.stopped ? (old ? SEV.blocker : SEV.warn) : SEV.none;
  const actor = mv ? ACTORS[mv.actorId] : null;

  return (
    <div style={{ borderBottom: `1px solid ${hairline}`, background: sev.tint }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, minHeight: 56, padding: "8px 12px", borderLeft: `3px solid ${sev.rule}`, paddingLeft: 9 }}>
        {selecting ? (
          <input type="checkbox" checked={selected} onChange={onSelect} aria-label={r.product.name} style={{ width: 20, height: 20 }} />
        ) : null}
        <Thumb status={r.product.photo} size={40} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="q-body" style={{ color: ink, overflow: "hidden", textOverflow: "ellipsis" }}>
            {r.product.name}{r.variant.name ? ` · ${r.variant.name}` : ""}
          </div>
          <div className="q-caption" style={{ color: inkSubtle }}>
            <span style={MONO}>{r.variant.sku}</span> · {categoryPath(r.categoryId)}
          </div>
          {r.stopped && mv ? (
            <div className="q-caption" style={{ color: sev.text, marginTop: 2 }}>
              On stop {since(mv.at)} · since {mv.at.slice(11, 16)}, {actor.name}
              {actor.type === "SYSTEM" ? " (system)" : actor.active ? "" : " (deactivated)"}
              {mv.reason ? ` · ${REASON_LABEL[mv.reason]}` : " · no reason given"}
            </div>
          ) : null}
          {r.unstocked ? (
            <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>
              No stock record at this branch — treated as unavailable, not as an error
            </div>
          ) : null}
          {r.unsupported ? (
            <div className="q-caption" style={{ color: inkMuted, marginTop: 2 }}>
              Quantity tracking is not supported yet — the schema accepts it, the service refuses it
            </div>
          ) : null}
        </div>

        <button
          type="button" onClick={onExplain} className="q-caption"
          style={{ background: "none", border: `1px solid ${hairline}`, color: inkMuted, cursor: "pointer", padding: "6px 8px", minHeight: 32 }}
        >
          Why
        </button>

        {canAdjust && !selecting && !r.unsupported ? (
          r.unstocked ? (
            <Button size="sm" variant="tertiary" style={{ minHeight: 44, minWidth: 110 }}>Add to stock</Button>
          ) : r.stopped ? (
            <Button size="sm" variant="primary" onClick={onResume} style={{ minHeight: 44, minWidth: 110 }}>Return</Button>
          ) : (
            <Button size="sm" variant="secondary" onClick={onStop} style={{ minHeight: 44, minWidth: 110 }}>Stop</Button>
          )
        ) : null}
      </div>

    </div>
  );
}

/* The availability explainer. A dish can be unbuyable for six independent
 * reasons and Delever offers no single place to see which. Each line links to
 * the screen that owns it. */
function ExplainerDrawer({ row, onClose, setView, reachable }) {
  if (!row) return null;
  const layers = explain(row);
  const first = layers.find((l) => !l.ok);
  return (
    <Drawer title={`Availability · ${row.product.name}`} onClose={onClose} width={520}>
      {/* The verdict before the working: an operator on the phone to a customer
        * needs the answer in the first line, not after seven rows of layers. */}
      <div
        className="q-body"
        style={{
          color: first ? "var(--q-error-text)" : "var(--q-success-text)",
          background: first ? "var(--q-error-tint)" : "var(--q-success-tint)",
          borderLeft: `3px solid ${first ? "var(--q-error)" : "var(--q-success)"}`,
          padding: "10px 12px",
        }}
      >
        {first ? first.line : "Buyable now — every layer resolves."}
      </div>
      <div className="q-body-sm" style={{ color: inkMuted, margin: "16px 0" }}>
        Resolved in the order ADR 0017's projection resolves it. The first failing layer is the answer;
        the ones below it still matter when it is fixed.
      </div>
      {layers.map((l) => (
        <div
          key={l.layer}
          style={{
            display: "flex", gap: 12, alignItems: "flex-start", padding: "10px 12px",
            borderLeft: `3px solid ${l.ok ? "transparent" : "var(--q-error)"}`,
            background: l.ok ? "transparent" : "var(--q-error-tint)",
            borderBottom: `1px solid ${hairline}`,
          }}
        >
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="q-caption" style={{ color: inkSubtle }}>{l.layer}</div>
            <div className="q-body-sm" style={{ color: l.ok ? ink : "var(--q-error-text)" }}>{l.line}</div>
          </div>
          {reachable.includes(l.view)
            ? <Link onClick={() => { setView(l.view); onClose(); }}>Open</Link>
            : <span className="q-caption" style={{ color: inkSubtle }}>Manager only</span>}
        </div>
      ))}
      <div style={{ marginTop: 16 }}>
        <NotBuilt>{NOT_BUILT.itemSchedule} The schedule layer would sit between the branch menu and the order type.</NotBuilt>
      </div>
    </Drawer>
  );
}

/* ── 4.10 Publication and channel readiness ────────────────────────────────
 * "Is this menu fit to show a customer, and make it live." Three stacked
 * regions on one page, because publishing is one decision informed by all
 * three — split into tabs, the operator publishes without reading the report.
 */
function PublicationView({ may, setView, setProductId }) {
  const [expanded, setExpanded] = useState([]);
  const [publishing, setPublishing] = useState(null);
  const canPublish = may("CATALOG_PUBLISH");

  /* Findings grouped by code with a count: forty instances of one code are one
   * fix, and a finding you cannot click is a finding somebody re-searches for
   * by hand. Severity comes off the finding, never off a client-side table. */
  const findings = useMemo(() => {
    const map = new Map();
    for (const p of PRODUCTS) {
      for (const c of blockersFor(p)) {
        if (!map.has(c)) map.set(c, { code: c, severity: "BLOCKER", items: [] });
        map.get(c).items.push(p);
      }
      for (const c of warningsFor(p)) {
        if (!map.has(c)) map.set(c, { code: c, severity: "WARNING", items: [] });
        map.get(c).items.push(p);
      }
    }
    return [...map.values()].sort((a, b) =>
      (a.severity === b.severity ? 0 : a.severity === "BLOCKER" ? -1 : 1) || b.items.length - a.items.length);
  }, []);

  const blockerCount = findings.filter((f) => f.severity === "BLOCKER").reduce((n, f) => n + f.items.length, 0);
  const unclassified = findings.find((f) => f.code === "FISCAL_CLASSIFICATION_MISSING")?.items.length ?? 0;
  const classified = PRODUCTS.filter((p) => p.status !== "ARCHIVED" && p.mxik).length;
  const total = PRODUCTS.filter((p) => p.status !== "ARCHIVED").length;
  const photographed = PRODUCTS.filter((p) => p.status !== "ARCHIVED" && p.photo === "AVAILABLE").length;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 24, minWidth: 0 }}>
      {/* Region 1 — readiness */}
      <div>
        <div style={{ display: "flex", gap: 16, alignItems: "baseline", flexWrap: "wrap" }}>
          <div className="q-subhead" style={{ color: blockerCount ? "var(--q-error-text)" : "var(--q-success-text)" }}>
            {blockerCount ? `${blockerCount} blocking problems` : "Ready to publish"}
          </div>
          <Button size="sm" variant="tertiary">Check again</Button>
          <Button size="sm" variant="ghost">Download report</Button>
        </div>

        <div style={{ display: "flex", gap: 24, marginTop: 16, flexWrap: "wrap" }}>
          <Coverage label="ИКПУ coverage" n={classified} total={total} />
          <Coverage label="Photo coverage" n={photographed} total={total} />
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 16 }}>
          {CATALOG_WARNINGS.map((w) => (
            <Banner key={w.code} tone="warn" title={w.text(unclassified)}>
              <span style={MONO}>{w.code}</span> — {w.note}
            </Banner>
          ))}
        </div>

        <div style={{ border: `1px solid ${hairline}`, background: canvas, marginTop: 16 }}>
          {findings.map((f) => {
            const open = expanded.includes(f.code);
            const blocker = f.severity === "BLOCKER";
            const s = blocker ? SEV.blocker : SEV.warn;
            return (
              <div key={f.code} style={{ borderBottom: `1px solid ${hairline}` }}>
                <button
                  type="button" onClick={() => setExpanded((e) => (open ? e.filter((x) => x !== f.code) : [...e, f.code]))}
                  style={{
                    display: "flex", width: "100%", gap: 12, alignItems: "center", textAlign: "left",
                    padding: "10px 12px", background: s.tint, border: "none",
                    borderLeft: `3px solid ${s.rule}`, cursor: "pointer",
                  }}
                >
                  <span className="q-emphasis" style={{ color: s.text, flex: 1, minWidth: 0 }}>
                    {FINDING_TEXT[f.code]}
                  </span>
                  <span className="q-caption" style={{ ...MONO, color: s.text }}>{f.code}</span>
                  <span className="q-caption q-tnum" style={{ color: s.text }}>{f.items.length}</span>
                </button>
                {open ? f.items.map((p) => (
                  <div key={p.id} style={{ display: "flex", gap: 12, alignItems: "center", padding: "8px 12px 8px 24px", borderTop: `1px solid ${hairline}` }}>
                    <span className="q-body-sm" style={{ color: ink, flex: 1, minWidth: 0 }}>{p.name}</span>
                    <span className="q-caption" style={{ ...MONO, color: inkSubtle }}>{p.code}</span>
                    <Link onClick={() => { setView("products"); setProductId(p.id); }}>Fix</Link>
                  </div>
                )) : null}
              </div>
            );
          })}
        </div>
      </div>

      {/* Region 2 — channels */}
      <div>
        <div className="q-subhead" style={{ color: ink, marginBottom: 12 }}>Channels</div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 12 }}>
          {CHANNELS.map((c) => {
            const live = LIVE_PUBLICATIONS[c.id];
            const archived = c.status === "ARCHIVED";
            return (
              <Card key={c.id} style={{ padding: 16, opacity: archived ? 0.6 : 1 }}>
                <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                  <span className="q-emphasis" style={{ color: ink }}>{c.name}</span>
                  <span className="q-caption" style={{ ...MONO, color: inkSubtle }}>{c.systemType}</span>
                </div>
                <div style={{ marginTop: 8 }}>
                  {archived ? <StatusPill tone="neutral">Archived</StatusPill>
                    : !live ? <StatusPill tone="failed">Menu not published</StatusPill>
                    : live.draftDiffers ? <StatusPill tone="pending">Draft differs</StatusPill>
                    : <StatusPill tone="active">Up to date</StatusPill>}
                </div>
                <div className="q-caption" style={{ color: inkMuted, marginTop: 8 }}>
                  {archived ? "Publication is refused for an archived channel."
                    : live
                      ? <>Live <span style={MONO}>{live.hash}</span> · {live.items} items · {dt(live.activatedAt)} · {ACTORS[live.createdBy].name}</>
                      : "Customers see nothing at all on this channel."}
                </div>
                {c.externallyPriced ? (
                  <div className="q-caption" style={{ color: inkMuted, marginTop: 6 }}>Prices come from the aggregator's own side.</div>
                ) : null}
                {canPublish && !archived ? (
                  <div style={{ marginTop: 12 }}>
                    <Button size="sm" onClick={() => setPublishing(c.id)}>Publish</Button>
                  </div>
                ) : null}
              </Card>
            );
          })}
        </div>
        <div style={{ marginTop: 12 }}>
          <NotBuilt>{NOT_BUILT.aggregatorPreview} Preview renders the storefront projection only, and says so rather than implying a rendering it cannot know.</NotBuilt>
        </div>
      </div>

      {/* Region 3 — history. A REJECTED row is kept and clickable: the service
          records rejections deliberately so "why did publishing fail an hour
          ago" has an answer, and the UI must not hide the row that answers it. */}
      <div>
        <div className="q-subhead" style={{ color: ink, marginBottom: 12 }}>Publication history</div>
        <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
          <table style={{ width: "100%", borderCollapse: "collapse", minWidth: 720 }}>
            <thead>
              <tr>
                <Th width={130}>When</Th><Th width={140}>Channel</Th><Th width={120}>Status</Th>
                <Th width={100}>Hash</Th><Th align="right" width={80}>Items</Th>
                <Th width={160}>Who</Th><Th align="right" width={90}>Problems</Th>
              </tr>
            </thead>
            <tbody>
              {PUBLICATION_HISTORY.map((h) => {
                const rejected = h.status === "REJECTED";
                return (
                  <tr key={h.id} style={{ background: rejected ? SEV.blocker.tint : undefined }}>
                    <td style={{ ...cell, borderLeft: `3px solid ${rejected ? SEV.blocker.rule : "transparent"}`, paddingLeft: 9 }} className="q-body-sm">
                      {dt(h.createdAt)}
                    </td>
                    <td style={cell} className="q-body-sm">{CHANNELS.find((c) => c.id === h.channel).name}</td>
                    <td style={cell}>
                      <StatusPill tone={h.status === "PUBLISHED" ? "active" : rejected ? "failed" : "neutral"}>
                        {h.status === "PUBLISHED" ? "Published" : rejected ? "Rejected" : "Retired"}
                      </StatusPill>
                    </td>
                    <td style={{ ...cell, ...MONO, color: inkMuted }} className="q-body-sm">{h.hash}</td>
                    <td style={{ ...cell, textAlign: "right" }} className="q-body-sm q-tnum">{h.items}</td>
                    <td style={cell} className="q-body-sm">{ACTORS[h.createdBy].name}</td>
                    <td style={{ ...cell, textAlign: "right" }} className="q-body-sm q-tnum">{h.problems || "—"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 8 }}>
          A failed publish returns 200 with the report — a considered no is a completed request, and it renders as a
          result panel rather than a red toast. Rollback republishes an earlier snapshot and never edits history;
          rolling back to a rejected publication is refused server-side.
        </div>
      </div>

      {publishing ? (() => {
        const c = CHANNELS.find((x) => x.id === publishing);
        const live = LIVE_PUBLICATIONS[c.id];
        return (
          <Confirm
            title={`Publish to ${c.name}`}
            confirmLabel="Publish"
            onClose={() => setPublishing(null)} onConfirm={() => setPublishing(null)}
            body={
              blockerCount
                ? `${blockerCount} blocking problems will be reported and nothing will be published. The report comes back as a result, not as an error.`
                : live
                  ? `148 items. This replaces the live menu ${live.hash} activated ${dt(live.activatedAt)}.`
                  : "148 items. This channel has no live menu — customers currently see nothing."
            }
          />
        );
      })() : null}
    </div>
  );
}

/* A chart is a plain div. Two of them here, because coverage is one number with
 * a denominator and anything more would be decoration. */
function Coverage({ label, n, total }) {
  const pct = Math.round((n / total) * 100);
  return (
    <div style={{ minWidth: 220 }}>
      <div className="q-caption" style={{ color: inkMuted }}>{label}</div>
      <div className="q-data-lg" style={{ color: ink }}>{n} of {total}</div>
      <div style={{ height: 4, background: surface1, marginTop: 6 }}>
        <div style={{ height: 4, width: `${pct}%`, background: pct === 100 ? "var(--q-success)" : blue }} />
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>{pct}%</div>
    </div>
  );
}

/* ── not prototyped, and why ───────────────────────────────────────────────
 * 4.2 the seven-tab product editor · 4.3 the category tree editor with drag
 * reordering · 4.4 modifier groups · 4.7 scheduled availability · 4.8 price
 * books and the bulk price change · 4.9 the photo-coverage wall · 4.11 Excel
 * and POS import · 4.12 the fiscal workbench · 4.13 reference data.
 *
 * All nine are specified in catalog.md and all nine are second-path screens for
 * this console's user, who is standing up during service. The four built here
 * are the ones a working shift touches: the library you search, the branch
 * matrix you correct, the stop list you hit, and the publication that makes any
 * of it visible. Building fifteen shallow views would have cost the one thing
 * this section is judged on — that a line cook can take a dish off the menu in
 * one tap and see, in the same screen, that it is still off four hours later.
 */
