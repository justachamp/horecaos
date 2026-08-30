/* Staff and access.
 *
 * The restaurant's own people. One rule governs every word on this screen: a
 * manager in Chilonzor never meets the vocabulary of the authorization model.
 * She does not grant a role at a resource scope. She gives Aziza a job, and she
 * gives it somewhere. The model keeps its names in the ids; the screen speaks
 * about jobs, and about where each one works.
 *
 * Three decisions carry the rest:
 *
 * 1. **Grouping by branch is the explanation.** The scope rule — a job covers
 *    downwards and never sideways or up — is never written out as a paragraph.
 *    It is demonstrated: the pinned groups at the top say what they reach
 *    («works in every branch of Osh Markazi: Chilonzor, Yunusobod») and a branch
 *    group contains exactly the people who work there. A person with jobs at two
 *    branches appears under both, each time showing only the job that belongs to
 *    that group. The one place the rule is spelled out in words is the empty
 *    state and the invite form, because that is where someone is about to need
 *    it.
 *
 * 2. **The list sorts by what has gone wrong, not by name.** The two states that
 *    mean the access model and reality have diverged — access taken away while
 *    the account still works, and an account switched off while the jobs are
 *    still attached — sort above everything, because they are the only urgent
 *    thing this screen can say. Alphabetical is one click away and becomes the
 *    default past roughly forty people, when the manager is looking someone up
 *    rather than scanning.
 *
 * 3. **What a job reaches is written as actions, never as menus.** «Cancel
 *    orders», not «the Orders section». That is what lets the job library answer
 *    "what will happen if I give her this?" — and it is why every job also shows
 *    what it does *not* reach, which is the half a manager is actually hesitating
 *    over and the half nobody ships.
 *
 * Not prototyped, and named at the bottom of the screens they belong to: shifts
 * and attendance (9.6), shared terminals and PINs (9.7), the global activity log
 * (9.8, present here only person-scoped) and the self-service profile (9.9).
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { NOW } from "./data";
import {
  Button, Card, Drawer, FilterBar, SearchInput, SectionHeader, Select, StatusPill, Tabs,
  uzs, dt, day, ink, inkMuted, inkSubtle, hairline, canvas, surface1, blue,
} from "./components";
import {
  ACTOR_ID, AREA_ORDER, BRANDS, CAPABILITIES, EVENTS, GAPS, JOBS, LOCATIONS,
  MODULES, PEOPLE, RULE,
} from "./Staff.data";

/* ── derivations ───────────────────────────────────────────────────────────
 * Everything below reads the fixtures. Nothing here is a second source of truth
 * for who may do what.
 */

const MS_MIN = 60_000;
const MS_DAY = 86_400_000;

const jobOf = (code) => JOBS.find((j) => j.code === code);
const capsOf = (code) => jobOf(code)?.caps || [];
const locOf = (id) => LOCATIONS.find((l) => l.id === id);
const brandOf = (id) => BRANDS.find((b) => b.id === id);

const phoneFmt = (p) => {
  const d = String(p || "").replace(/\D/g, "");
  if (d.length !== 12) return p || "—";
  return `+${d.slice(0, 3)} ${d.slice(3, 5)} ${d.slice(5, 8)} ${d.slice(8, 10)} ${d.slice(10)}`;
};

const initials = (name) =>
  name.split(/[\s-]+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();

/** Relative for the recent past, a date once it stops being useful as elapsed. */
const rel = (iso) => {
  if (!iso) return "—";
  const mins = Math.round((new Date(NOW) - new Date(iso)) / MS_MIN);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  const h = Math.round(mins / 60);
  if (h < 24) return `${h} h ago`;
  return dm(iso);
};

/** DD.MM — the short form, always derived from day() and never hand-built. */
const dm = (iso) => day(iso).slice(0, 5);

const daysUntil = (d) => Math.ceil((new Date(`${d}T23:59:00`) - new Date(NOW)) / MS_DAY);
const daysSince = (iso) => Math.floor((new Date(NOW) - new Date(iso)) / MS_DAY);

/** Where a job is given, said the way a manager says it. */
function whereLabel(a) {
  if (a.scopeType === "company") return "Whole company";
  if (a.scopeType === "brand") return brandOf(a.scopeId)?.name || a.scopeId;
  return locOf(a.scopeId)?.name || a.scopeId;
}

/** The whole model, as one predicate: a job reaches down and never sideways.
 *  Nothing on screen says this; every affordance obeys it. */
function reaches(holder, target) {
  if (holder.scopeType === "company") return true;
  if (holder.scopeType === "brand") {
    if (target.scopeType === "company") return false;
    if (target.scopeType === "brand") return target.scopeId === holder.scopeId;
    return locOf(target.scopeId)?.brandId === holder.scopeId;
  }
  return target.scopeType === "branch" && target.scopeId === holder.scopeId;
}

/** «Chilonzor +2» — the first place, and how many others there are. The rest
 *  are on the person's own record, which is where somebody comparing them will
 *  already be standing. */
function whereSummary(p) {
  const places = [...new Set(activeOf(p).map(whereLabel))];
  if (!places.length) return "—";
  return places.length > 1 ? `${places[0]} +${places.length - 1}` : places[0];
}

const activeOf = (p) => p.assignments.filter((a) => a.status === "ACTIVE");
const revokedOf = (p) => p.assignments.filter((a) => a.status === "REVOKED");

/** What the signed-in person can do at a given place, as a set of ids. */
function actorCapsAt(actor, target) {
  const set = new Set();
  activeOf(actor).forEach((a) => {
    if (reaches(a, target)) capsOf(a.jobCode).forEach((c) => set.add(c));
  });
  return set;
}

/** The server refuses a job that reaches further than the granter's own. That
 *  refusal must never reach a user: the job is absent from the picker, not
 *  disabled with a tooltip explaining why she is not trusted. */
function canConfer(actor, jobCode, target) {
  const mine = actorCapsAt(actor, target);
  return capsOf(jobCode).every((c) => mine.has(c));
}

const canManageAt = (actor, target) => actorCapsAt(actor, target).has("iam.grant.manage");

/** Can the signed-in person touch this assignment at all? */
const inCover = (actor, a) => canManageAt(actor, a) && canConfer(actor, a.jobCode, a);

/* Severity. Weight 0 and 1 are the two states that mean the access model and
 * reality have diverged; weight 4 is separated from 0 because "never had a job"
 * and "had one taken away" are different situations with different fixes. */
function severity(p) {
  const active = activeOf(p);
  const revoked = revokedOf(p);
  if (revoked.length && !active.length && p.accountEnabled) {
    const r = revoked[revoked.length - 1];
    return {
      w: 0, rule: "var(--q-error)", tint: "var(--q-error-tint)",
      pill: { tone: "failed", label: "Access taken away" },
      why: `Can still sign in — the screen will be empty. ${r.revokedReason}`,
    };
  }
  if (!p.accountEnabled) {
    return {
      w: 1, rule: "var(--q-warning)", tint: "var(--q-warning-tint)",
      pill: { tone: "suspended", label: "Account switched off" },
      why: `${p.disabledReason} · switched off ${dm(p.disabledAt)}`,
    };
  }
  const ending = active.filter((a) => a.validUntil).sort((x, y) => x.validUntil.localeCompare(y.validUntil))[0];
  if (ending && daysUntil(ending.validUntil) <= 7) {
    const d = daysUntil(ending.validUntil);
    return {
      w: 2, rule: "var(--q-warning)", tint: "var(--q-warning-tint)",
      pill: { tone: "pending", label: `Ends ${dm(ending.validUntil)}` },
      why: `${jobOf(ending.jobCode).name} in ${whereLabel(ending)} stops in ${d} day${d === 1 ? "" : "s"}. Nobody has renewed it.`,
    };
  }
  if (!p.lastSignInAt && p.invitedAt && daysSince(p.invitedAt) > 3) {
    return {
      w: 3, rule: "var(--q-warning)", tint: "var(--q-warning-tint)",
      pill: { tone: "pending", label: "Invited" },
      why: `Invited ${dm(p.invitedAt)}, ${daysSince(p.invitedAt)} days ago, and has never signed in.`,
    };
  }
  if (!active.length) {
    return {
      w: 4, rule: "var(--q-warning)", tint: "var(--q-warning-tint)",
      pill: { tone: "neutral", label: "No job" },
      why: "Can sign in, and will see nothing. No job has ever been given.",
    };
  }
  return { w: 5, rule: "transparent", tint: "transparent", pill: { tone: "active", label: "Active" }, why: null };
}

/* ── local pieces ──────────────────────────────────────────────────────────
 * Not in components.jsx, and not general enough to belong there.
 */

function JobChip({ assignment, showWhere = true }) {
  const j = jobOf(assignment.jobCode);
  return (
    <span
      className="q-caption"
      style={{
        display: "inline-flex", alignItems: "baseline", gap: 4, padding: "2px 8px",
        border: `1px solid ${hairline}`, background: surface1, color: ink, whiteSpace: "nowrap",
      }}
    >
      {j.name}
      {showWhere ? <span style={{ color: inkSubtle }}>· {whereLabel(assignment)}</span> : null}
    </span>
  );
}

/** A named hole in the backend, said out loud where the missing thing would be.
 *  A prototype that quietly invents a field teaches everyone the field exists. */
/** One table header cell. Three tables on this screen, one definition. */
function Th({ children, align = "left" }) {
  return (
    <th
      className="q-caption"
      style={{
        textAlign: align, padding: "10px 16px", background: surface1, color: inkMuted,
        fontWeight: 600, borderBottom: `1px solid ${hairline}`, whiteSpace: "nowrap",
      }}
    >
      {children}
    </th>
  );
}

/** A filter pill. Dark fill when it is the one in force, because a filter you
 *  cannot see the state of is a filter you will forget you set. */
function Pill({ on, onClick, children }) {
  return (
    <button
      type="button" onClick={onClick} className="q-caption"
      style={{
        height: 30, padding: "0 10px", cursor: "pointer", border: `1px solid ${on ? ink : hairline}`,
        background: on ? ink : canvas, color: on ? "#fff" : ink,
      }}
    >
      {children}
    </button>
  );
}

function Gap({ children }) {
  return (
    <div
      className="q-caption"
      style={{
        borderLeft: `3px solid ${hairline}`, paddingLeft: 12, color: inkSubtle, maxWidth: 720,
      }}
    >
      {children}
    </div>
  );
}

function Note({ tone = "info", title, children }) {
  const tint = tone === "warn" ? "var(--q-warning-tint)" : "var(--q-info-tint)";
  const rule = tone === "warn" ? "var(--q-warning)" : "var(--q-primary)";
  return (
    <div style={{ background: tint, borderLeft: `3px solid ${rule}`, padding: "10px 12px" }}>
      {title ? <div className="q-emphasis" style={{ color: ink }}>{title}</div> : null}
      <div className="q-body-sm" style={{ color: inkMuted, marginTop: title ? 2 : 0 }}>{children}</div>
    </div>
  );
}

/** A centred dialog. 0 corners, hairline, no shadow — depth is the overlay. */
function Modal({ title, onClose, children, width = 560 }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(22,22,22,0.5)", zIndex: 60,
        display: "flex", alignItems: "flex-start", justifyContent: "center", padding: "64px 16px",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width, maxWidth: "100%", background: canvas, border: `1px solid ${hairline}`,
          maxHeight: "calc(100vh - 128px)", display: "flex", flexDirection: "column",
        }}
      >
        <div style={{ padding: "16px 24px", borderBottom: `1px solid ${hairline}` }}>
          <div className="q-subhead" style={{ color: ink }}>{title}</div>
        </div>
        <div style={{ padding: 24, overflowY: "auto" }}>{children}</div>
      </div>
    </div>
  );
}

/** Every change to someone's access carries a reason. The schema requires it and
 *  the audit fact throws without it, so it is a control and not a note. A label
 *  that can be answered gets an answer; «Comment» gets «asdf». */
function ReasonField({ value, onChange }) {
  const over = value.length > 200;
  return (
    <label style={{ display: "block" }}>
      <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>Why? Required — it goes into the log</div>
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={2}
        placeholder="For example: moved to Yunusobod"
        className="q-body-sm"
        style={{
          width: "100%", padding: 8, color: ink, background: canvas, resize: "vertical",
          border: `1px solid ${over ? "var(--q-error)" : hairline}`, borderRadius: "var(--q-radius)", outline: "none",
        }}
      />
      <div className="q-caption q-tnum" style={{ color: over ? "var(--q-error-text)" : inkSubtle, marginTop: 2 }}>
        {value.length} / 200
      </div>
    </label>
  );
}

function TextField({ label, value, onChange, placeholder, hint, mono, type = "text" }) {
  return (
    <label style={{ display: "block" }}>
      <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>{label}</div>
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        className="q-body-sm"
        style={{
          width: "100%", height: 40, padding: "0 8px", color: ink, background: canvas,
          fontFamily: mono ? "var(--q-font-mono)" : undefined,
          border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)", outline: "none",
        }}
      />
      {hint ? <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>{hint}</div> : null}
    </label>
  );
}

/** The capability set, and the only place in the console it is ever visible —
 *  as sentences, grouped by area, one line per thing. Never a dotted code. */
function Reaches({ caps, columns = 2 }) {
  const groups = AREA_ORDER
    .map((area) => ({ area, items: caps.filter((c) => CAPABILITIES[c]?.area === area) }))
    .filter((gr) => gr.items.length);
  return (
    <div style={{ display: "grid", gridTemplateColumns: `repeat(${columns}, minmax(0,1fr))`, gap: 16 }}>
      {groups.map((gr) => (
        <div key={gr.area}>
          <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>{gr.area}</div>
          {gr.items.map((c) => (
            <div key={c} className="q-body-sm" style={{ color: ink, padding: "2px 0" }}>{CAPABILITIES[c].says}</div>
          ))}
        </div>
      ))}
    </div>
  );
}

/** The complement, ordered by how often people hesitate over it: money first,
 *  then the menu, then everything else. Capped where space is short. */
const cannotFor = (caps, cap) => {
  const rest = Object.keys(CAPABILITIES).filter((c) => !caps.includes(c));
  rest.sort((a, b) => CAPABILITIES[a].asked - CAPABILITIES[b].asked);
  return cap ? rest.slice(0, cap) : rest;
};

function Disclosure({ label, children }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="q-body-sm"
        style={{ background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0 }}
      >
        {open ? "Hide what this reaches" : label}
      </button>
      {open ? <div style={{ marginTop: 12 }}>{children}</div> : null}
    </div>
  );
}

/* ── the job dialog and the invite form ────────────────────────────────────
 * One picker, two hosts. Both show only the jobs the signed-in person can
 * actually confer, both constrain the place to what she covers, and both preview
 * the consequence before the button is pressed.
 */

function scopeOptionsFor(actor, level) {
  const targets =
    level === "company" ? [{ scopeType: "company", scopeId: null }]
      : level === "brand" ? BRANDS.map((b) => ({ scopeType: "brand", scopeId: b.id }))
        : LOCATIONS.map((l) => ({ scopeType: "branch", scopeId: l.id }));
  return targets.filter((t) => canManageAt(actor, t));
}

function JobPicker({ actor, jobCode, setJobCode, scopeKey, setScopeKey }) {
  const level = jobCode ? jobOf(jobCode).level : null;
  const places = level ? scopeOptionsFor(actor, level) : [];
  const chosen = places.find((p) => `${p.scopeType}:${p.scopeId}` === scopeKey) || places[0];

  /* A job is offered only where the actor could confer it somewhere. Absent, not
   * disabled: an option that refuses itself teaches nothing. */
  const offered = JOBS.filter((j) =>
    scopeOptionsFor(actor, j.level).some((t) => canConfer(actor, j.code, t)));

  return (
    <>
      <div>
        <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>Job</div>
        <select
          value={jobCode || ""}
          onChange={(e) => { setJobCode(e.target.value); setScopeKey(null); }}
          className="q-body-sm"
          style={{
            width: "100%", height: 40, padding: "0 8px", background: canvas, color: ink,
            border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)",
          }}
        >
          <option value="">Choose a job</option>
          {["company", "brand", "branch"].map((lv) => {
            const inLevel = offered.filter((j) => j.level === lv);
            if (!inLevel.length) return null;
            const head = lv === "company" ? "Whole company" : lv === "brand" ? "One brand" : "One branch";
            return (
              <optgroup key={lv} label={head}>
                {inLevel.map((j) => <option key={j.code} value={j.code}>{j.name} — {j.blurb}</option>)}
              </optgroup>
            );
          })}
        </select>
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>
          Only the jobs you can give out yourself are here. {RULE}
        </div>
      </div>

      {jobCode ? (
        <div>
          <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>Where</div>
          {places.length === 1 ? (
            <div className="q-body-sm" style={{ color: ink, padding: "10px 8px", border: `1px solid ${hairline}`, background: surface1 }}>
              {whereLabel(places[0])}
              <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>the only place you can give this</span>
            </div>
          ) : (
            <select
              value={chosen ? `${chosen.scopeType}:${chosen.scopeId}` : ""}
              onChange={(e) => setScopeKey(e.target.value)}
              className="q-body-sm"
              style={{
                width: "100%", height: 40, padding: "0 8px", background: canvas, color: ink,
                border: `1px solid ${hairline}`, borderRadius: "var(--q-radius)",
              }}
            >
              {places.map((p) => (
                <option key={`${p.scopeType}:${p.scopeId}`} value={`${p.scopeType}:${p.scopeId}`}>
                  {whereLabel(p)}
                </option>
              ))}
            </select>
          )}
        </div>
      ) : null}

      {jobCode && chosen ? <JobPreview jobCode={jobCode} target={chosen} /> : null}
    </>
  );
}

/** The consequence, before the button. The "will not be able to" half is the one
 *  a manager is actually hesitating over, and it is the half nobody ships. */
function JobPreview({ jobCode, target }) {
  const j = jobOf(jobCode);
  const can = j.caps.filter((c) => CAPABILITIES[c]).sort((a, b) => CAPABILITIES[b].asked - CAPABILITIES[a].asked);
  const cannot = cannotFor(j.caps, 5);
  const say = (list) => list.map((c) => CAPABILITIES[c].says.toLowerCase()).join(", ");
  return (
    <div style={{ border: `1px solid ${hairline}`, background: surface1, padding: 12 }}>
      <div className="q-emphasis" style={{ color: ink }}>{j.name} — {whereLabel(target)}</div>
      <div className="q-body-sm" style={{ color: inkMuted, marginTop: 6 }}>
        <span style={{ color: ink }}>Will be able to:</span> {say(can.slice(0, 6))}.
      </div>
      <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
        <span style={{ color: ink }}>Will not be able to:</span> {say(cannot)}.
      </div>
      {target.scopeType === "branch" ? (
        <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
          Only in {whereLabel(target)}. Not in the other branches.
        </div>
      ) : null}
    </div>
  );
}

function InviteModal({ actor, onClose, onInvite }) {
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("+998 ");
  const [email, setEmail] = useState("");
  const [jobCode, setJobCode] = useState("");
  const [scopeKey, setScopeKey] = useState(null);
  const [until, setUntil] = useState("");
  const [reason, setReason] = useState("");

  const digits = phone.replace(/\D/g, "");
  const dup = PEOPLE.find((p) => p.phone.replace(/\D/g, "") === digits);
  const level = jobCode ? jobOf(jobCode).level : null;
  const places = level ? scopeOptionsFor(actor, level) : [];
  const target = places.find((p) => `${p.scopeType}:${p.scopeId}` === scopeKey) || places[0];
  const ready = name.trim() && digits.length === 12 && !dup && jobCode && target && reason.trim();

  return (
    <Modal title="Invite someone" onClose={onClose}>
      <div style={{ display: "grid", gap: 16 }}>
        <TextField label="Name and surname" value={name} onChange={setName} placeholder="Aziza Karimova" />
        <TextField
          label="Phone" value={phone} onChange={setPhone} mono placeholder="+998 90 123 45 67"
          hint={dup ? undefined : "The identifier this market actually uses."}
        />
        {dup ? (
          <div className="q-body-sm" style={{ color: "var(--q-error-text)", marginTop: -12 }}>
            {dup.name} already has access. <span style={{ color: blue }}>Open their record</span>
          </div>
        ) : null}
        <TextField
          label="Email — optional" value={email} onChange={setEmail} type="email"
          placeholder="ixtiyoriy"
          hint="Most branch staff have no work email. Without one, the invitation comes back as a link you read out or forward."
        />
        <JobPicker actor={actor} jobCode={jobCode} setJobCode={setJobCode} scopeKey={scopeKey} setScopeKey={setScopeKey} />
        <TextField label="Temporary access — until" value={until} onChange={setUntil} type="date" hint="Leave empty for permanent." />
        <ReasonField value={reason} onChange={setReason} />
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            disabled={!ready}
            onClick={() => onInvite({ name: name.trim(), phone, jobCode, target, until: until || null, reason })}
          >
            Invite
          </Button>
        </div>
        <Gap>{GAPS.identity}</Gap>
      </div>
    </Modal>
  );
}

/* ── people (9.1) ──────────────────────────────────────────────────────────*/

const STATUS_FILTERS = [
  { id: "all", label: "All", test: () => true },
  { id: "active", label: "Active", test: (p) => severity(p).w === 5 },
  { id: "off", label: "Switched off", test: (p) => !p.accountEnabled },
  { id: "invited", label: "Invited", test: (p) => !p.lastSignInAt },
  { id: "nojob", label: "No job", test: (p) => !activeOf(p).length },
];

function People({ people, setPeople, actor, onOpen, jobFilter, setJobFilter }) {
  const multiBranch = LOCATIONS.length > 1;
  const [pivot, setPivot] = useState(multiBranch ? "branch" : "all");
  const [status, setStatus] = useState("all");
  const [branch, setBranch] = useState("all");
  const [search, setSearch] = useState("");
  const [sortBy, setSortBy] = useState("attention");
  const [selected, setSelected] = useState(() => new Set());
  const [collapsed, setCollapsed] = useState(() => new Set());
  const [hovered, setHovered] = useState(null);
  const [cursor, setCursor] = useState(0);
  const [invite, setInvite] = useState(false);
  const [confirm, setConfirm] = useState(null);
  const [outcome, setOutcome] = useState(null);
  const [toast, setToast] = useState(null);
  const searchRef = useRef(null);

  /* Counts are computed before filtering, so they do not collapse as the
   * selection narrows and stop being a map of the whole. */
  const counts = useMemo(() => {
    const c = {};
    STATUS_FILTERS.forEach((f) => { c[f.id] = people.filter(f.test).length; });
    return c;
  }, [people]);

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    const qd = q.replace(/\D/g, "");
    return people
      .filter((p) => STATUS_FILTERS.find((f) => f.id === status).test(p))
      .filter((p) => (jobFilter === "all" ? true : p.assignments.some((a) => a.jobCode === jobFilter && a.status === "ACTIVE")))
      .filter((p) => {
        if (branch === "all") return true;
        return activeOf(p).some((a) => reaches(a, { scopeType: "branch", scopeId: branch }));
      })
      .filter((p) => {
        if (!q) return true;
        if (p.name.toLowerCase().includes(q)) return true;
        return qd.length >= 3 && p.phone.replace(/\D/g, "").includes(qd);
      })
      .sort((a, b) => {
        if (sortBy === "name") return a.name.localeCompare(b.name, "ru");
        const d = severity(a).w - severity(b).w;
        return d !== 0 ? d : a.name.localeCompare(b.name, "ru");
      });
  }, [people, status, branch, search, sortBy, jobFilter]);

  /* Keyboard. Nothing destructive has a shortcut: this is not a queue, and a
   * muscle-memory keystroke that removes someone's access buys no speed. */
  useEffect(() => {
    const onKey = (e) => {
      const typing = ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement?.tagName);
      if (e.key === "/" && !typing) { e.preventDefault(); searchRef.current?.querySelector("input")?.focus(); return; }
      if (e.key === "Escape" && typing) { setSearch(""); document.activeElement.blur(); return; }
      if (typing || invite || confirm) return;
      if (e.key === "j") setCursor((c) => Math.min(c + 1, rows.length - 1));
      if (e.key === "k") setCursor((c) => Math.max(c - 1, 0));
      if (e.key === "Enter" && rows[cursor]) onOpen(rows[cursor].id);
      if (e.key === "x" && rows[cursor]) toggle(rows[cursor].id);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    n.has(id) ? n.delete(id) : n.add(id);
    return n;
  });

  const selectedPeople = rows.filter((p) => selected.has(p.id));

  /* A bulk action is offered only when it is valid for every selected row. A
   * mixed selection is refused with its arithmetic stated — never quietly
   * applied to the valid subset, which is how someone discovers three days later
   * that two of eight did not happen. */
  const suspendCheck = (() => {
    const bad = selectedPeople.filter((p) =>
      p.id === actor.id || !activeOf(p).some((a) => inCover(actor, a)));
    return {
      ok: selectedPeople.length > 0 && bad.length === 0,
      bad,
      msg: bad.length
        ? `${bad.length} of ${selectedPeople.length} selected cannot be suspended — ${bad.map((p) => (p.id === actor.id ? "you cannot suspend yourself" : `${p.name} has no access to take away`)).join("; ")}.`
        : null,
    };
  })();

  const doSuspend = (targets, reason) => {
    setPeople((prev) => prev.map((p) => {
      if (!targets.some((t) => t.id === p.id)) return p;
      return {
        ...p,
        assignments: p.assignments.map((a) =>
          a.status === "ACTIVE" && inCover(actor, a)
            ? { ...a, status: "REVOKED", revokedAt: NOW, revokedBy: actor.id, revokedReason: reason }
            : a),
      };
    }));
    setSelected(new Set());
    setConfirm(null);
    setOutcome({
      done: targets.map((t) => t.name),
      refused: [],
      reason,
    });
  };

  const groups = useMemo(() => {
    if (pivot === "all") return [{ id: "flat", label: null, rows, filterScope: null }];
    const out = [];
    /* Nobody's branch, because nobody has given them one — or somebody took it
     * away. Pinned above everything, because grouping must not bury the two
     * states that mean the access model and reality have come apart. */
    const orphan = rows.filter((p) => !activeOf(p).length);
    if (orphan.length) {
      out.push({
        id: "orphan", label: "No job",
        meta: "They can sign in and they will see nothing",
        rows: orphan, filterScope: null,
      });
    }
    const company = rows.filter((p) => activeOf(p).some((a) => a.scopeType === "company"));
    if (company.length) {
      out.push({
        id: "company", label: "Whole company", meta: "Works in every branch",
        rows: company, filterScope: { scopeType: "company" },
      });
    }
    BRANDS.forEach((b) => {
      const inB = rows.filter((p) => activeOf(p).some((a) => a.scopeType === "brand" && a.scopeId === b.id));
      if (inB.length) {
        out.push({
          id: b.id, label: `Brand ${b.name}`,
          meta: `Works in every branch of ${b.name}: ${LOCATIONS.filter((l) => l.brandId === b.id).map((l) => l.name).join(", ")}`,
          rows: inB, filterScope: { scopeType: "brand", scopeId: b.id },
        });
      }
    });
    LOCATIONS.forEach((l) => {
      const inL = rows.filter((p) => activeOf(p).some((a) => a.scopeType === "branch" && a.scopeId === l.id));
      if (inL.length) {
        out.push({
          id: l.id, label: l.name, meta: l.street, closed: l.forceClosed,
          rows: inL, filterScope: { scopeType: "branch", scopeId: l.id },
        });
      }
    });
    return out;
  }, [rows, pivot]);

  const showOrders = people.some((p) => activeOf(p).some((a) => capsOf(a.jobCode).includes("order.approve")));

  return (
    <>
      <SectionHeader
        title="People"
        description="Who works here, what each of them may do, and who has access they should not."
        right={<Button onClick={() => setInvite(true)}>Invite someone</Button>}
      />

      <FilterBar>
        <div style={{ display: "inline-flex", border: `1px solid ${hairline}` }}>
          {[{ id: "all", label: "All" }, { id: "branch", label: "By branch" }].map((o) => (
            <Pill key={o.id} on={pivot === o.id} onClick={() => setPivot(o.id)}>{o.label}</Pill>
          ))}
        </div>
        <span style={{ width: 1, height: 24, background: hairline }} />
        {STATUS_FILTERS.map((f) => (
          <Pill key={f.id} on={status === f.id} onClick={() => setStatus(f.id)}>
            {f.label} <span className="q-tnum" style={{ opacity: 0.7 }}>{counts[f.id]}</span>
          </Pill>
        ))}
        <span style={{ width: 1, height: 24, background: hairline }} />
        <Select
          label="Branch" value={branch} onChange={setBranch}
          options={[{ value: "all", label: `All branches (${people.length})` },
            ...LOCATIONS.map((l) => ({
              value: l.id,
              label: `${l.name} (${people.filter((p) => activeOf(p).some((a) => reaches(a, { scopeType: "branch", scopeId: l.id }))).length})`,
            }))]}
        />
        <Select
          label="Job" value={jobFilter} onChange={setJobFilter}
          options={[{ value: "all", label: "All jobs" },
            ...JOBS.map((j) => ({
              value: j.code,
              label: `${j.name} (${people.filter((p) => activeOf(p).some((a) => a.jobCode === j.code)).length})`,
            }))]}
        />
        <div ref={searchRef}>
          <SearchInput value={search} onChange={setSearch} placeholder="Name or phone   /" />
        </div>
        <Select
          label="Sort" value={sortBy} onChange={setSortBy}
          options={[{ value: "attention", label: "What needs attention" }, { value: "name", label: "By name" }]}
        />
      </FilterBar>

      {selected.size ? (
        <div
          style={{
            display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap", padding: 12,
            background: "var(--q-info-tint)", borderLeft: `3px solid ${blue}`,
            border: `1px solid ${hairline}`, borderBottom: "none",
          }}
        >
          <span className="q-body-sm" style={{ color: ink }}>
            Selected {selected.size} of {rows.length} filtered
          </span>
          <Button
            size="sm" variant="secondary" disabled={!suspendCheck.ok}
            onClick={() => setConfirm({ kind: "suspend", targets: selectedPeople })}
          >
            Suspend access
          </Button>
          <Button size="sm" variant="tertiary" onClick={() => setToast("Export runs as a file of name, jobs, where and status. Never the internal identifier.")}>
            Export the list
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>Clear</Button>
          {suspendCheck.msg ? (
            <span className="q-caption" style={{ color: "var(--q-error-text)", width: "100%" }}>{suspendCheck.msg}</span>
          ) : null}
          <span className="q-caption" style={{ color: inkMuted, width: "100%" }}>{GAPS.bulk}</span>
        </div>
      ) : null}

      {outcome ? (
        <div style={{ border: `1px solid ${hairline}`, borderBottom: "none", padding: 12, background: canvas }}>
          <Note title={`${outcome.done.length} done, ${outcome.refused.length} refused`}>
            {outcome.done.join(", ")} — access taken away. Reason recorded: “{outcome.reason}”.
            Each one is its own record in the log, so any of them can be answered for on its own.{" "}
            <span><button type="button" onClick={() => setOutcome(null)} className="q-body-sm" style={{ background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0 }}>Dismiss</button></span>
          </Note>
        </div>
      ) : null}

      {toast ? (
        <div style={{ border: `1px solid ${hairline}`, borderBottom: "none", padding: 12, background: canvas }}>
          <Note>{toast} <button type="button" onClick={() => setToast(null)} className="q-body-sm" style={{ background: "transparent", border: "none", color: blue, cursor: "pointer" }}>Dismiss</button></Note>
        </div>
      ) : null}

      <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr>
              {["", "Person", "Job", "Where", "Access", "Last sign-in", ...(showOrders ? ["Orders today"] : []), ""].map((h, i) => (
                <Th key={i} align={h === "Orders today" ? "right" : "left"}>
                  {h === "" && i === 0 ? (
                    <input
                      type="checkbox" style={{ accentColor: blue }}
                      checked={rows.length > 0 && selected.size === rows.length}
                      onChange={(e) => setSelected(e.target.checked ? new Set(rows.map((p) => p.id)) : new Set())}
                    />
                  ) : h}
                </Th>
              ))}
            </tr>
          </thead>
          {groups.map((gr) => {
            const shut = collapsed.has(gr.id);
            return (
              <tbody key={gr.id}>
                {gr.label ? (
                  <tr>
                    <td colSpan={showOrders ? 8 : 7} style={{ padding: 0, borderBottom: `1px solid ${hairline}` }}>
                      <button
                        type="button"
                        onClick={() => setCollapsed((c) => { const n = new Set(c); n.has(gr.id) ? n.delete(gr.id) : n.add(gr.id); return n; })}
                        style={{
                          display: "flex", alignItems: "baseline", gap: 8, width: "100%", textAlign: "left",
                          padding: "8px 16px", background: surface1, border: "none", cursor: "pointer",
                          borderLeft: gr.closed ? "3px solid var(--q-warning)" : "3px solid transparent",
                        }}
                      >
                        <span className="q-emphasis" style={{ color: ink }}>{gr.label}</span>
                        <span className="q-caption q-tnum" style={{ color: inkMuted }}>{gr.rows.length}</span>
                        <span className="q-caption" style={{ color: inkSubtle }}>{gr.meta}</span>
                        {gr.closed ? (
                          <span className="q-caption" style={{ color: "var(--q-warning-text)", marginLeft: "auto" }}>
                            Force-closed since {rel(gr.closed.since)} — {gr.closed.reason}
                          </span>
                        ) : null}
                      </button>
                    </td>
                  </tr>
                ) : null}
                {shut ? null : gr.rows.map((p) => (
                  <Row
                    key={`${gr.id}-${p.id}`} p={p} actor={actor} group={gr} showOrders={showOrders}
                    selected={selected.has(p.id)} onToggle={() => toggle(p.id)}
                    cursored={rows[cursor]?.id === p.id}
                    hovered={hovered === `${gr.id}-${p.id}`} setHovered={(v) => setHovered(v ? `${gr.id}-${p.id}` : null)}
                    onOpen={() => onOpen(p.id)}
                    onSuspend={() => setConfirm({ kind: "suspend", targets: [p] })}
                    onRestore={() => setConfirm({ kind: "restore", targets: [p] })}
                    onCopy={() => setToast(`${phoneFmt(p.phone)} copied.`)}
                  />
                ))}
              </tbody>
            );
          })}
          {!rows.length ? (
            <tbody>
              <tr>
                <td colSpan={showOrders ? 8 : 7} style={{ padding: 32, textAlign: "center" }}>
                  <div className="q-body" style={{ color: ink }}>Nobody found</div>
                  <div style={{ marginTop: 12 }}>
                    <Button size="sm" variant="tertiary" onClick={() => { setStatus("all"); setBranch("all"); setJobFilter("all"); setSearch(""); }}>
                      Clear the filters
                    </Button>
                  </div>
                </td>
              </tr>
            </tbody>
          ) : null}
        </table>
      </div>

      <div style={{ display: "grid", gap: 8, marginTop: 16 }}>
        <Gap>{RULE}</Gap>
        <Gap>{GAPS.identity}</Gap>
        <Gap>{GAPS.lastSignIn}</Gap>
        {showOrders ? <Gap>{GAPS.ordersToday}</Gap> : null}
        <Gap>{GAPS.enforce}</Gap>
      </div>

      {invite ? (
        <InviteModal
          actor={actor}
          onClose={() => setInvite(false)}
          onInvite={(f) => {
            setPeople((prev) => [...prev, {
              id: `p-new-${prev.length}`, name: f.name, phone: f.phone,
              subject: "—", lastSignInAt: null, invitedAt: NOW, ordersToday: null, accountEnabled: true,
              assignments: [{
                id: `as-new-${prev.length}`, jobCode: f.jobCode, scopeType: f.target.scopeType,
                scopeId: f.target.scopeId, validFrom: NOW.slice(0, 10), validUntil: f.until,
                grantedBy: actor.id, reason: f.reason, status: "ACTIVE",
              }],
            }]);
            setInvite(false);
            setToast(`${f.name} is invited. The link is horecaos.uz/i/8f2a-41c — copy it and send it, most branch staff have no email.`);
          }}
        />
      ) : null}

      {confirm ? (
        <ConfirmAccess
          confirm={confirm} actor={actor}
          onClose={() => setConfirm(null)}
          onDone={(reason) => doSuspend(confirm.targets, reason)}
        />
      ) : null}
    </>
  );
}

function Row({ p, actor, group, showOrders, selected, onToggle, cursored, hovered, setHovered, onOpen, onSuspend, onRestore, onCopy }) {
  const s = severity(p);
  const isYou = p.id === actor.id;
  const shown = group.filterScope
    ? activeOf(p).filter((a) => (group.filterScope.scopeType === "company"
      ? a.scopeType === "company"
      : a.scopeType === group.filterScope.scopeType && a.scopeId === group.filterScope.scopeId))
    : activeOf(p);
  const jobs = shown.length ? shown : activeOf(p);
  const manageable = activeOf(p).some((a) => inCover(actor, a)) && !isYou;
  const restorable = !activeOf(p).length && revokedOf(p).some((a) => inCover(actor, a)) && !isYou;

  const cell = { padding: "10px 16px", verticalAlign: "top", color: ink };
  return (
    <tr
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      style={{
        borderBottom: `1px solid ${hairline}`,
        background: s.w <= 4 ? s.tint : cursored ? "var(--q-info-tint)" : hovered ? surface1 : canvas,
      }}
    >
      <td style={{ ...cell, borderLeft: `3px solid ${s.rule}`, width: 40 }}>
        <input type="checkbox" checked={selected} onChange={onToggle} style={{ accentColor: blue }} />
      </td>
      <td style={cell}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
          <button
            type="button" onClick={onOpen} className="q-body-sm"
            style={{
              background: "transparent", border: "none", padding: 0, cursor: "pointer", textAlign: "left",
              color: s.w === 1 ? inkMuted : ink, maxWidth: 260, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
            }}
            title={p.name}
          >
            {p.name}
          </button>
          {isYou ? <span className="q-caption" style={{ color: inkSubtle, border: `1px solid ${hairline}`, padding: "0 6px" }}>You</span> : null}
        </div>
        <div className="q-caption" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)", marginTop: 2 }}>
          {phoneFmt(p.phone)}
        </div>
        {s.why ? (
          <div className="q-caption" style={{ color: s.w <= 1 ? "var(--q-error-text)" : "var(--q-warning-text)", marginTop: 4, maxWidth: 340 }}>
            {s.why}
          </div>
        ) : null}
      </td>
      <td style={cell}>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 4 }}>
          {jobs.length
            ? jobs.map((a) => <JobChip key={a.id} assignment={a} showWhere={!group.filterScope} />)
            : <span className="q-body-sm" style={{ color: inkSubtle }}>—</span>}
        </div>
      </td>
      <td style={cell}>
        <span className="q-body-sm" style={{ color: inkMuted }}>{whereSummary(p)}</span>
      </td>
      <td style={cell}><StatusPill tone={s.pill.tone}>{s.pill.label}</StatusPill></td>
      <td style={{ ...cell }}>
        <span className="q-body-sm q-tnum" style={{ color: p.lastSignInAt ? inkMuted : inkSubtle }}>{rel(p.lastSignInAt)}</span>
      </td>
      {showOrders ? (
        <td style={{ ...cell, textAlign: "right" }}>
          <span className="q-body-sm q-tnum" style={{ color: p.ordersToday === null ? inkSubtle : ink }}>
            {p.ordersToday === null ? "—" : p.ordersToday}
          </span>
        </td>
      ) : null}
      <td style={{ ...cell, textAlign: "right", whiteSpace: "nowrap", width: 220 }}>
        {hovered ? (
          <span style={{ display: "inline-flex", gap: 8 }}>
            <RowAction onClick={onOpen}>Open</RowAction>
            {manageable ? <RowAction onClick={onSuspend}>Suspend</RowAction> : null}
            {restorable ? <RowAction onClick={onRestore}>Give back</RowAction> : null}
            {!p.lastSignInAt ? <RowAction onClick={() => {}}>Send again</RowAction> : null}
            <RowAction onClick={onCopy}>Copy phone</RowAction>
          </span>
        ) : null}
      </td>
    </tr>
  );
}

function RowAction({ children, onClick }) {
  return (
    <button
      type="button" onClick={onClick} className="q-caption"
      style={{ background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0 }}
    >
      {children}
    </button>
  );
}

/** The confirmation says what actually stops working, and it says the part that
 *  is uncomfortable: taking away every job is not the same as switching the
 *  account off, and the person can still sign in to an empty console. */
function ConfirmAccess({ confirm, actor, onClose, onDone }) {
  const [reason, setReason] = useState("");
  const names = confirm.targets.map((t) => t.name);
  const one = confirm.targets.length === 1;
  return (
    <Modal title={one ? `Suspend ${names[0]}'s access?` : `Suspend access for ${names.length} people?`} onClose={onClose}>
      <div style={{ display: "grid", gap: 16 }}>
        <div className="q-body-sm" style={{ color: ink }}>
          {one ? names[0] : names.join(", ")} will no longer be able to do anything in the system.
        </div>
        <Note tone="warn" title="They can still sign in">
          The screen will be empty. {GAPS.disable}
        </Note>
        <div>
          <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>What stops working</div>
          {confirm.targets.map((t) => (
            <div key={t.id} className="q-body-sm" style={{ color: inkMuted, padding: "2px 0" }}>
              {t.name} — {activeOf(t).filter((a) => inCover(actor, a)).map((a) => `${jobOf(a.jobCode).name} in ${whereLabel(a)}`).join("; ")}
            </div>
          ))}
        </div>
        <ReasonField value={reason} onChange={setReason} />
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button variant="danger" disabled={!reason.trim() || reason.length > 200} onClick={() => onDone(reason.trim())}>
            Suspend access
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/* ── one person (9.2) ──────────────────────────────────────────────────────*/

const PERSON_TABS = [
  { id: "access", label: "Access" },
  { id: "activity", label: "Activity" },
];

function Person({ person, actor, onBack, setPeople }) {
  const [tab, setTab] = useState("access");
  const [event, setEvent] = useState(null);
  const [adding, setAdding] = useState(false);
  const s = severity(person);
  const since = person.assignments.map((a) => a.validFrom).sort()[0];
  const events = EVENTS.filter((e) => e.actorId === person.id);

  return (
    <>
      <button
        type="button" onClick={onBack} className="q-body-sm"
        style={{ background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0, marginBottom: 8 }}
      >
        ← People
      </button>

      <div style={{ display: "flex", gap: 16, alignItems: "flex-start", marginBottom: 16 }}>
        <div
          className="q-body"
          style={{
            width: 48, height: 48, background: surface1, border: `1px solid ${hairline}`,
            display: "flex", alignItems: "center", justifyContent: "center", color: inkMuted, flexShrink: 0,
          }}
        >
          {initials(person.name)}
        </div>
        <div style={{ minWidth: 0 }}>
          <h1 className="q-title" style={{ margin: 0, color: ink }}>{person.name}</h1>
          <div style={{ display: "flex", gap: 12, alignItems: "center", marginTop: 6, flexWrap: "wrap" }}>
            <span className="q-body-sm" style={{ color: inkMuted, fontFamily: "var(--q-font-mono)" }}>{phoneFmt(person.phone)}</span>
            <StatusPill tone={s.pill.tone}>{s.pill.label}</StatusPill>
            <span className="q-caption" style={{ color: inkSubtle }}>
              In the system since {since ? day(since) : "—"}
            </span>
          </div>
          {s.why ? <div className="q-caption" style={{ color: s.w <= 1 ? "var(--q-error-text)" : "var(--q-warning-text)", marginTop: 6 }}>{s.why}</div> : null}
        </div>
      </div>

      <Tabs tabs={PERSON_TABS} active={tab} onChange={setTab} />

      {tab === "access" ? (
        <AccessTab person={person} actor={actor} setPeople={setPeople} adding={adding} setAdding={setAdding} />
      ) : null}
      {tab === "activity" ? <ActivityTab person={person} events={events} onOpen={setEvent} /> : null}

      {event ? (
        <Drawer title="What happened" onClose={() => setEvent(null)} width={480}>
          <div style={{ display: "grid", gap: 16 }}>
            <div>
              <div className="q-body" style={{ color: ink }}>{event.what}</div>
              <div className="q-caption q-tnum" style={{ color: inkSubtle, marginTop: 4 }}>
                {dt(event.at)} · {event.where}
              </div>
            </div>
            <StatusPill tone={OUTCOME[event.outcome].tone}>{OUTCOME[event.outcome].label}</StatusPill>
            <div>
              <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>Why</div>
              <div className="q-body-sm" style={{ color: ink }}>{event.reason}</div>
            </div>
            {event.amountMinor ? (
              <div>
                <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>Amount</div>
                <div className="q-body-sm q-tnum" style={{ color: ink }}>{uzs(event.amountMinor)}</div>
              </div>
            ) : null}
            {event.bulkOf ? (
              <Note title={`Part of one action on ${event.bulkOf.count} people`}>
                Each of the {event.bulkOf.count} is its own record. That is what makes “was Aziza in that batch?”
                answerable afterwards.
              </Note>
            ) : null}
            <div>
              <div className="q-caption" style={{ color: inkMuted, marginBottom: 4 }}>What changed</div>
              <Gap>{GAPS.diff}</Gap>
            </div>
          </div>
        </Drawer>
      ) : null}
    </>
  );
}

function AccessTab({ person, actor, setPeople, adding, setAdding }) {
  const [jobCode, setJobCode] = useState("");
  const [scopeKey, setScopeKey] = useState(null);
  const [reason, setReason] = useState("");
  const [removing, setRemoving] = useState(null);
  const active = activeOf(person);
  const past = revokedOf(person);
  const order = { company: 0, brand: 1, branch: 2 };
  const sorted = [...active].sort((a, b) => order[a.scopeType] - order[b.scopeType]);
  const level = jobCode ? jobOf(jobCode).level : null;
  const places = level ? scopeOptionsFor(actor, level) : [];
  const target = places.find((p) => `${p.scopeType}:${p.scopeId}` === scopeKey) || places[0];

  const remove = (a) => {
    setPeople((prev) => prev.map((p) => (p.id !== person.id ? p : {
      ...p,
      assignments: p.assignments.map((x) => (x.id === a.id
        ? { ...x, status: "REVOKED", revokedAt: NOW, revokedBy: actor.id, revokedReason: removing.reason }
        : x)),
    })));
    setRemoving(null);
  };

  return (
    <div style={{ display: "grid", gap: 16 }}>
      {!active.length ? (
        <Card>
          <div className="q-body" style={{ color: ink }}>
            {person.name.split(" ")[0]} has no job. They can sign in, and they will see nothing.
          </div>
          <div style={{ marginTop: 16 }}><Button onClick={() => setAdding(true)}>Give a job</Button></div>
        </Card>
      ) : null}

      {sorted.map((a) => {
        const j = jobOf(a.jobCode);
        const mine = inCover(actor, a);
        const last = active.length === 1 && person.id === actor.id;
        return (
          <Card key={a.id} style={{ padding: 16 }}>
            <div style={{ display: "flex", gap: 12, alignItems: "baseline", flexWrap: "wrap" }}>
              <span className="q-emphasis" style={{ color: ink }}>{j.name}</span>
              <span className="q-body-sm" style={{ color: inkMuted }}>{whereLabel(a)}</span>
              {a.validUntil ? (
                <span className="q-caption" style={{ color: "var(--q-warning-text)" }}>
                  until {day(a.validUntil)} — {daysUntil(a.validUntil)} days left
                </span>
              ) : null}
              <span style={{ marginLeft: "auto", display: "inline-flex", gap: 12 }}>
                {mine && !last ? <RowAction onClick={() => setRemoving({ a, reason: "" })}>Take it away</RowAction> : null}
                {mine && a.validUntil ? <RowAction onClick={() => {}}>Extend</RowAction> : null}
                {!mine ? <span className="q-caption" style={{ color: inkSubtle }}>outside your access</span> : null}
              </span>
            </div>
            <div className="q-caption" style={{ color: inkSubtle, marginTop: 6 }}>
              From {day(a.validFrom)}{a.validUntil ? "" : " · no end date"} · given by {nameOf(a.grantedBy)} · “{a.reason}”
            </div>
            <div style={{ marginTop: 12 }}>
              <Disclosure label="What this reaches">
                <Reaches caps={j.caps} />
                <div className="q-caption" style={{ color: inkSubtle, marginTop: 12 }}>
                  {a.scopeType === "branch"
                    ? `All of it in ${whereLabel(a)}, and only there.`
                    : a.scopeType === "brand"
                      ? `All of it in every branch of ${whereLabel(a)}.`
                      : "All of it in every branch of the company."}
                </div>
              </Disclosure>
            </div>
          </Card>
        );
      })}

      {active.length ? (
        <div><Button variant="tertiary" onClick={() => setAdding(true)}>Give another job</Button></div>
      ) : null}

      {past.length ? (
        <Card style={{ padding: 16, background: surface1 }}>
          <div className="q-emphasis" style={{ color: ink, marginBottom: 8 }}>Taken away</div>
          {past.map((a) => (
            <div key={a.id} className="q-body-sm" style={{ color: inkMuted, padding: "2px 0" }}>
              {jobOf(a.jobCode).name} in {whereLabel(a)} — until {day(a.revokedAt.slice(0, 10))}, by {nameOf(a.revokedBy)}. “{a.revokedReason}”
            </div>
          ))}
          <div style={{ marginTop: 12 }}><Gap>{GAPS.history}</Gap></div>
        </Card>
      ) : null}

      <Card style={{ padding: 16 }}>
        <div className="q-caption" style={{ color: inkSubtle }}>Internal identifier</div>
        <div className="q-body-sm" style={{ color: inkSubtle, fontFamily: "var(--q-font-mono)", margin: "2px 0 8px" }}>
          {person.subject.slice(0, 10)}… <RowAction onClick={() => {}}>Copy</RowAction>
        </div>
        <Gap>
          A support artefact, and the only place on this record it appears. The specification puts it behind a
          Security tab together with sign-in method, open sessions and the terminal PIN — none of which is
          prototyped, because none of them exists. {GAPS.security} Shifts are its fourth tab, and are not
          prototyped either (§11.11).
        </Gap>
      </Card>

      {adding ? (
        <Modal title={`Give ${person.name.split(" ")[0]} a job`} onClose={() => setAdding(false)}>
          <div style={{ display: "grid", gap: 16 }}>
            <JobPicker actor={actor} jobCode={jobCode} setJobCode={setJobCode} scopeKey={scopeKey} setScopeKey={setScopeKey} />
            <ReasonField value={reason} onChange={setReason} />
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
              <Button
                disabled={!jobCode || !target || !reason.trim()}
                onClick={() => {
                  setPeople((prev) => prev.map((p) => (p.id !== person.id ? p : {
                    ...p,
                    assignments: [...p.assignments, {
                      id: `as-x-${p.assignments.length}`, jobCode, scopeType: target.scopeType,
                      scopeId: target.scopeId, validFrom: NOW.slice(0, 10), validUntil: null,
                      grantedBy: actor.id, reason: reason.trim(), status: "ACTIVE",
                    }],
                  })));
                  setAdding(false); setJobCode(""); setReason("");
                }}
              >
                Give the job
              </Button>
            </div>
          </div>
        </Modal>
      ) : null}

      {removing ? (
        <Modal title={`Take away ${jobOf(removing.a.jobCode).name} in ${whereLabel(removing.a)}?`} onClose={() => setRemoving(null)}>
          <div style={{ display: "grid", gap: 16 }}>
            <div className="q-body-sm" style={{ color: ink }}>
              {person.name} will stop being able to do this in {whereLabel(removing.a)}. Other jobs stay as they are.
            </div>
            <ReasonField value={removing.reason} onChange={(v) => setRemoving({ ...removing, reason: v })} />
            <div style={{ display: "flex", gap: 8, justifyContent: "flex-end" }}>
              <Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button>
              <Button variant="danger" disabled={!removing.reason.trim()} onClick={() => remove(removing.a)}>Take it away</Button>
            </div>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}

const OUTCOME = {
  done: { tone: "healthy", label: "Done" },
  refused: { tone: "failed", label: "Refused" },
  waiting: { tone: "pending", label: "Waiting for approval" },
  error: { tone: "failed", label: "Error" },
};

const nameOf = (id) => PEOPLE.find((p) => p.id === id)?.name || "—";

function ActivityTab({ person, events, onOpen }) {
  /* The counts are derived from the rows below them, so they cannot disagree
   * with the table the reader is looking at. */
  const refused = events.filter((e) => e.outcome === "refused").length;
  const waiting = events.filter((e) => e.outcome === "waiting").length;

  return (
    <div style={{ display: "grid", gap: 16 }}>
      <div className="q-body-sm" style={{ color: inkMuted }}>
        Last 30 days: <span style={{ color: ink }}>{events.length} actions</span> ·{" "}
        <span style={{ color: refused ? "var(--q-error-text)" : ink }}>{refused} refused</span> ·{" "}
        <span style={{ color: ink }}>{waiting} waiting for approval</span>
      </div>

      <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto" }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr>
              {["When", "What", "Where", "Result", "Why"].map((h) => <Th key={h}>{h}</Th>)}
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr
                key={e.id} onClick={() => onOpen(e)}
                style={{ borderBottom: `1px solid ${hairline}`, cursor: "pointer" }}
              >
                <td className="q-body-sm q-tnum" style={{ padding: "10px 16px", color: inkMuted, whiteSpace: "nowrap", fontFamily: "var(--q-font-mono)" }}>
                  {dt(e.at)}
                </td>
                <td className="q-body-sm" style={{ padding: "10px 16px", color: ink }}>
                  {e.what}{" "}
                  {e.bulkOf ? <span className="q-caption" style={{ color: inkSubtle, marginLeft: 8 }}>part of {e.bulkOf.count}</span> : null}
                </td>
                <td className="q-body-sm" style={{ padding: "10px 16px", color: inkMuted, whiteSpace: "nowrap" }}>{e.where}</td>
                <td style={{ padding: "10px 16px" }}><StatusPill tone={OUTCOME[e.outcome].tone}>{OUTCOME[e.outcome].label}</StatusPill></td>
                <td className="q-body-sm" style={{ padding: "10px 16px", color: inkMuted, maxWidth: 280, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {e.reason}
                </td>
              </tr>
            ))}
            {!events.length ? (
              <tr><td colSpan={5} className="q-body-sm" style={{ padding: 24, color: inkMuted, textAlign: "center" }}>
                Nothing happened in this period
              </td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
      <div className="q-caption" style={{ color: inkSubtle }}>Reading the log is itself recorded.</div>
      <Gap>The whole-company log is view 9.8 and is not prototyped; this is its person-scoped slice.</Gap>
    </div>
  );
}

/* ── the job library (9.4) and the access check (9.5) ──────────────────────*/

function Jobs({ people, actor, onOpenPerson, onFilterPeopleByJob }) {
  const [open, setOpen] = useState("location-manager");
  const holders = (code) => people.filter((p) => activeOf(p).some((a) => a.jobCode === code));
  const level = { company: 0, brand: 1, branch: 2 };
  const sorted = [...JOBS].sort((a, b) =>
    level[a.level] - level[b.level] || holders(b.code).length - holders(a.code).length);
  const j = jobOf(open);
  const areas = [...new Set(j.caps.map((c) => CAPABILITIES[c].area))];

  return (
    <>
      <SectionHeader
        title="Jobs"
        description="What each job actually lets a person do, before you give it to anyone."
      />

      <div style={{ border: `1px solid ${hairline}`, background: canvas, overflowX: "auto", marginBottom: 24 }}>
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr>
              {["Job", "Given at", "What it reaches", "People"].map((h) => (
                <Th key={h} align={h === "People" ? "right" : "left"}>{h}</Th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sorted.map((job) => {
              const on = job.code === open;
              return (
                <tr
                  key={job.code} onClick={() => setOpen(job.code)}
                  style={{ borderBottom: `1px solid ${hairline}`, cursor: "pointer", background: on ? "var(--q-info-tint)" : canvas }}
                >
                  <td style={{ padding: "10px 16px", verticalAlign: "top" }}>
                    <div className="q-body-sm" style={{ color: ink }}>{job.name}</div>
                    <div className="q-caption" style={{ color: inkSubtle, marginTop: 2, maxWidth: 420 }}>{job.blurb}</div>
                  </td>
                  <td style={{ padding: "10px 16px", verticalAlign: "top" }}>
                    <span className="q-caption" style={{ border: `1px solid ${hairline}`, padding: "2px 8px", color: ink }}>
                      {job.level === "company" ? "Whole company" : job.level === "brand" ? "One brand" : "One branch"}
                    </span>
                  </td>
                  <td className="q-body-sm" style={{ padding: "10px 16px", color: inkMuted, verticalAlign: "top" }}>
                    {[...new Set(job.caps.map((c) => CAPABILITIES[c].area))].join(", ")}
                  </td>
                  <td style={{ padding: "10px 16px", textAlign: "right", verticalAlign: "top" }}>
                    <button
                      type="button"
                      onClick={(e) => { e.stopPropagation(); onFilterPeopleByJob(job.code); }}
                      className="q-body-sm q-tnum"
                      style={{ background: "transparent", border: "none", color: holders(job.code).length ? blue : inkSubtle, cursor: "pointer", padding: 0 }}
                    >
                      {holders(job.code).length}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <Card style={{ marginBottom: 24 }}>
        <div style={{ display: "flex", gap: 12, alignItems: "baseline", marginBottom: 16, flexWrap: "wrap" }}>
          <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{j.name}</h2>
          <span className="q-body-sm" style={{ color: inkMuted }}>{j.blurb}</span>
          <span style={{ marginLeft: "auto" }}>
            <Button size="sm" variant="tertiary" onClick={() => onFilterPeopleByJob(j.code)}>Give this to someone</Button>
          </span>
        </div>

        <div className="q-emphasis" style={{ color: ink, marginBottom: 8 }}>What this reaches</div>
        <Reaches caps={j.caps} columns={Math.min(3, Math.max(1, areas.length))} />

        <div className="q-emphasis" style={{ color: ink, margin: "24px 0 8px" }}>What it does not reach</div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(2, minmax(0,1fr))", gap: 4 }}>
          {cannotFor(j.caps).map((c) => (
            <div key={c} className="q-body-sm" style={{ color: inkMuted }}>{CAPABILITIES[c].says}</div>
          ))}
          {!cannotFor(j.caps).length ? <div className="q-body-sm" style={{ color: inkMuted }}>Nothing. This job reaches everything.</div> : null}
        </div>

        <div className="q-emphasis" style={{ color: ink, margin: "24px 0 8px" }}>Who has it</div>
        {holders(j.code).length ? (
          <div>
            {holders(j.code).map((p) => (
              <div key={p.id} style={{ display: "flex", gap: 12, padding: "6px 0", borderBottom: `1px solid ${hairline}` }}>
                <button
                  type="button" onClick={() => onOpenPerson(p.id)} className="q-body-sm"
                  style={{ background: "transparent", border: "none", color: blue, cursor: "pointer", padding: 0 }}
                >
                  {p.name}
                </button>
                <span className="q-body-sm" style={{ color: inkMuted }}>
                  {activeOf(p).filter((a) => a.jobCode === j.code).map(whereLabel).join(", ")}
                </span>
                <span className="q-caption" style={{ color: inkSubtle, marginLeft: "auto" }}>
                  since {day(activeOf(p).find((a) => a.jobCode === j.code).validFrom)}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <div className="q-body-sm" style={{ color: inkMuted }}>Nobody yet.</div>
        )}
        <div style={{ marginTop: 16 }}>
          <Gap>Jobs are code-owned in v1: no editing, no copying, no deleting, and no permission grid until tenants can define their own (§11.4, ADR 0025).</Gap>
        </div>
      </Card>

      <AccessCheck people={people} actor={actor} />
    </>
  );
}

/** The negative answer is the whole point: it is where the rule gets taught, at
 *  the moment somebody needs to learn it, and it ends in the thing that fixes
 *  it. Nowhere does it say scope, and nowhere does it say inheritance. */
function AccessCheck({ people, actor }) {
  const [who, setWho] = useState("p-nilufar");
  const [what, setWhat] = useState("order.cancel");
  const [whereKey, setWhereKey] = useState("branch:lo-mirobod");
  const [answer, setAnswer] = useState(null);

  const places = [
    { scopeType: "company", scopeId: null },
    ...BRANDS.map((b) => ({ scopeType: "brand", scopeId: b.id })),
    ...LOCATIONS.map((l) => ({ scopeType: "branch", scopeId: l.id })),
  ];

  const check = () => {
    const p = people.find((x) => x.id === who);
    const target = places.find((t) => `${t.scopeType}:${t.scopeId}` === whereKey);
    const covering = activeOf(p).find((a) => reaches(a, target) && capsOf(a.jobCode).includes(what));
    const elsewhere = activeOf(p).find((a) => capsOf(a.jobCode).includes(what));
    const brandId = target.scopeType === "branch" ? locOf(target.scopeId).brandId
      : target.scopeType === "brand" ? target.scopeId : null;
    const moduleOff = CAPABILITIES[what].area === "Couriers" && brandId && !MODULES[brandId].couriers;

    if (covering && moduleOff) {
      setAnswer({
        kind: "plan",
        head: "This is not in your plan.",
        body: `${p.name} has the access. The couriers module is not switched on for ${brandOf(brandId).name}.`,
        fix: "Switch the module on",
      });
    } else if (covering) {
      setAnswer({
        kind: "yes",
        head: "Yes.",
        body: `${p.name} can ${CAPABILITIES[what].says.toLowerCase()} in ${whereLabel(target)}.`,
        chain: `Because: ${jobOf(covering.jobCode).name}, given ${whereLabel(covering) === "Whole company" ? "for the whole company" : `in ${whereLabel(covering)}`}, since ${day(covering.validFrom)}.`,
      });
    } else if (elsewhere) {
      setAnswer({
        kind: "no",
        head: "No.",
        body: `${p.name} cannot ${CAPABILITIES[what].says.toLowerCase()} in ${whereLabel(target)}.`,
        chain: `They have ${jobOf(elsewhere.jobCode).name}, but only in ${whereLabel(elsewhere)}. A job given in one place works there and in what sits under it — never in the place next door.`,
        fix: `Give the job in ${whereLabel(target)}`,
      });
    } else {
      setAnswer({
        kind: "no",
        head: "No.",
        body: `${p.name} cannot ${CAPABILITIES[what].says.toLowerCase()} anywhere.`,
        chain: "No job they hold reaches this.",
        fix: "Give a job that reaches it",
      });
    }
  };

  const tone = answer?.kind === "yes" ? "var(--q-success)" : answer?.kind === "plan" ? "var(--q-primary)" : "var(--q-error)";
  const tint = answer?.kind === "yes" ? "var(--q-success-tint)" : answer?.kind === "plan" ? "var(--q-info-tint)" : "var(--q-error-tint)";

  return (
    <Card>
      <h2 className="q-subhead" style={{ margin: "0 0 4px", color: ink }}>Check what someone can do</h2>
      <p className="q-body-sm" style={{ margin: "0 0 16px", color: inkMuted, maxWidth: 640 }}>
        For when the answer to “why can’t Nilufar cancel that order?” has to come from somewhere other than an argument.
      </p>
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
        <Select label="Who" value={who} onChange={setWho} options={people.map((p) => ({ value: p.id, label: p.name }))} />
        <Select
          label="What" value={what} onChange={setWhat}
          options={Object.keys(CAPABILITIES).map((c) => ({ value: c, label: CAPABILITIES[c].says }))}
        />
        <Select
          label="Where" value={whereKey} onChange={setWhereKey}
          options={places.map((t) => ({ value: `${t.scopeType}:${t.scopeId}`, label: whereLabel(t) }))}
        />
        <Button size="sm" onClick={check}>Check</Button>
      </div>

      {answer ? (
        <div style={{ marginTop: 16, background: tint, borderLeft: `3px solid ${tone}`, padding: 12 }}>
          <div className="q-body" style={{ color: ink }}>
            <span className="q-emphasis">{answer.head}</span> {answer.body}
          </div>
          {answer.chain ? <div className="q-body-sm" style={{ color: inkMuted, marginTop: 6 }}>{answer.chain}</div> : null}
          {answer.fix ? (
            <div style={{ marginTop: 12 }}><Button size="sm" variant="tertiary" onClick={() => {}}>{answer.fix}</Button></div>
          ) : null}
        </div>
      ) : null}
      <div style={{ marginTop: 16 }}>
        <Gap>No endpoint answers this today — the authorization service only returns the signed-in person's own view (§6, ADR 0025). {GAPS.entitlement}</Gap>
      </div>
    </Card>
  );
}

/* ── the screen ────────────────────────────────────────────────────────────*/

export default function Staff({ staffId, setStaffId, tab, setTab }) {
  const [people, setPeople] = useState(PEOPLE);
  const [jobFilter, setJobFilter] = useState("all");
  const view = tab === "jobs" ? "jobs" : "people";
  const actor = people.find((p) => p.id === ACTOR_ID);
  const person = staffId ? people.find((p) => p.id === staffId) : null;

  if (staffId && !person) {
    return (
      <Card>
        <div className="q-body" style={{ color: ink }}>There is no such person</div>
        <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>
          The link that brought you here has gone stale. It was probably in a record of something that happened.
        </div>
        <div style={{ marginTop: 16 }}><Button variant="tertiary" onClick={() => setStaffId(null)}>Back to people</Button></div>
      </Card>
    );
  }

  if (person) return <Person person={person} actor={actor} setPeople={setPeople} onBack={() => setStaffId(null)} />;

  return (
    <>
      <Tabs tabs={[{ id: "people", label: "People" }, { id: "jobs", label: "Jobs" }]} active={view} onChange={setTab} />

      {view === "people" ? (
        <People
          people={people} setPeople={setPeople} actor={actor}
          jobFilter={jobFilter} setJobFilter={setJobFilter}
          onOpen={setStaffId}
        />
      ) : (
        <Jobs
          people={people} actor={actor}
          onOpenPerson={setStaffId}
          onFilterPeopleByJob={(code) => { setJobFilter(code); setTab("people"); }}
        />
      )}
    </>
  );
}
