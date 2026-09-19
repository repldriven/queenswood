// Paying someone (payee → amount → review → Face ID → sent) and moving
// money between the customer's own accounts.
import { useState, useEffect } from "react";
import { gbp, Ic, Top, Field, Pad } from "./ui.jsx";
import * as api from "./api.js";

function Amount({ value, onKey, label }) {
  return (
    <div style={{ padding: "0 8px" }}>
      <div className="eyebrow" style={{ textAlign: "center", marginBottom: 6 }}>
        {label}
      </div>
      <div
        className="big num"
        style={{ color: value ? "var(--fg)" : "var(--muted)" }}
      >
        <small>£</small>
        {value || "0"}
      </div>
      <div style={{ height: 18 }}></div>
      <Pad onKey={onKey} />
    </div>
  );
}

// A keypad-driven amount: at most two decimals and eight characters,
// with a leading zero replaced rather than kept.
const useAmt = () => {
  const [v, setV] = useState("");
  const key = (k) => {
    if (k === "⌫") return setV(v.slice(0, -1));
    if (k === ".") {
      if (!v.includes(".")) setV((v || "0") + ".");
      return;
    }
    if (v.includes(".") && v.split(".")[1].length >= 2) return;
    if (v.length > 7) return;
    setV(v === "0" ? k : v + k);
  };
  return [v, key, setV];
};

// The Face ID interstitial: shown for a moment, then done.
function Confirm({ onDone, label }) {
  useEffect(() => {
    const t = setTimeout(onDone, 1500);
    return () => clearTimeout(t);
  }, []);
  return (
    <div className="scr" data-screen-label="Face ID">
      <div className="body" style={{ display: "grid", placeItems: "center" }}>
        <div style={{ textAlign: "center", color: "var(--lime)" }}>
          <div style={{ animation: "pop .5s" }}>{Ic.face}</div>
          <div style={{ color: "var(--fg-2)", marginTop: 14 }}>{label}</div>
        </div>
      </div>
    </div>
  );
}

const initials = (name) =>
  name
    .split(" ")
    .map((w) => w[0])
    .slice(0, 2)
    .join("");

// What the payee's bank said about the name, in the customer's words,
// and what they may do about it.
const COP = {
  match: {
    icon: Ic.tick,
    text: (np) => (
      <>
        <b>Name matches.</b> The account is held by {np.name}.
      </>
    ),
    proceed: "Continue",
  },
  "close-match": {
    icon: Ic.lock,
    text: (np, held) => (
      <>
        <b>Close, but not exact.</b> Their bank holds this account as{" "}
        <b>{held}</b>.
      </>
    ),
    proceed: (held) => `Pay ${held}`,
  },
  "no-match": {
    icon: Ic.lock,
    text: (np) => (
      <>
        <b>Name doesn't match.</b> Their bank says this account isn't held by{" "}
        {np.name}. Check the details with them before paying.
      </>
    ),
    proceed: "Pay anyway",
  },
  unavailable: {
    icon: Ic.lock,
    text: () => (
      <>
        <b>Couldn't check the name.</b> Their bank didn't answer. Check the
        details before paying.
      </>
    ),
    proceed: "Pay anyway",
  },
};

export function Pay({ S, params, pop, send }) {
  const [step, setStep] = useState(0);
  const [payee, setPayee] = useState(null);
  const [q, setQ] = useState("");
  const [np, setNp] = useState({ name: "", sort: "", num: "" });
  // "idle", "checking", or what the bank answered: { outcome, nameHeld }.
  const [cop, setCop] = useState("idle");
  const [err, setErr] = useState(null);
  const [submission, setSubmission] = useState(null);
  const [amt, key] = useAmt();
  const [ref, setRef] = useState("");
  const from =
    S.accounts.find((a) => a.id === params.from) ||
    S.accounts.find((a) => a.kind === "cur") ||
    S.accounts[0];
  const n = +amt || 0;
  const check = async () => {
    setCop("checking");
    setErr(null);
    try {
      setCop(
        await api.checkPayee({ name: np.name, sort: np.sort, num: np.num }),
      );
    } catch (e) {
      setCop("idle");
      setErr(e.message);
    }
  };
  // The payee as checked, under the name their bank holds where that is
  // what the customer chose to pay.
  const proceed = (name) => {
    setPayee({ id: "new", name, sort: np.sort, num: np.num });
    setStep(2);
  };
  const review = () => {
    setSubmission(api.idempotencyKey());
    setErr(null);
    setStep(3);
  };
  const npOk =
    np.name.length > 1 &&
    np.sort.replace(/\D/g, "").length === 6 &&
    np.num.replace(/\D/g, "").length === 8;
  const fmtSort = (s) =>
    s
      .replace(/\D/g, "")
      .slice(0, 6)
      .replace(/(\d{2})(?=\d)/g, "$1-");
  const list = S.payees.filter((p) =>
    p.name.toLowerCase().includes(q.toLowerCase()),
  );
  if (!from)
    return (
      <div className="scr" data-screen-label="Pay · no account">
        <Top onBack={pop} title="Pay" close />
        <div className="body">
          <div className="card">Open an account to pay someone from it.</div>
        </div>
      </div>
    );
  if (step === 0)
    return (
      <div className="scr" data-screen-label="Pay · choose payee">
        <Top onBack={pop} title="Pay" close />
        <div className="body">
          <input
            className="inp"
            placeholder="Search payees"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ marginBottom: 6 }}
          />
          <button className="li" onClick={() => setStep(1)}>
            <span className="av lime">{Ic.plus}</span>
            <span className="ttl">New payee</span>
            <span style={{ marginLeft: "auto", color: "var(--muted)" }}>
              {Ic.chev}
            </span>
          </button>
          <div className="eyebrow" style={{ margin: "18px 0 4px" }}>
            Recent
          </div>
          <div className="list">
            {list.map((p) => (
              <button
                key={p.id}
                className="li"
                onClick={() => {
                  setPayee(p);
                  setStep(2);
                }}
              >
                <span className="av">{initials(p.name)}</span>
                <span>
                  <div className="ttl">{p.name}</div>
                  <div className="meta">Last paid {p.last}</div>
                </span>
                <span style={{ marginLeft: "auto", color: "var(--muted)" }}>
                  {Ic.chev}
                </span>
              </button>
            ))}
          </div>
        </div>
      </div>
    );
  if (step === 1)
    return (
      <div className="scr" data-screen-label="Pay · new payee">
        <Top onBack={() => setStep(0)} title="New payee" />
        <div className="body">
          <Field label="Their name">
            <input
              className="inp"
              value={np.name}
              onChange={(e) => {
                setNp({ ...np, name: e.target.value });
                setCop("idle");
              }}
              placeholder="Full name or business"
            />
          </Field>
          <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
            <Field label="Sort code">
              <input
                className="inp mono"
                inputMode="numeric"
                placeholder="00-00-00"
                value={np.sort}
                onChange={(e) => {
                  setNp({ ...np, sort: fmtSort(e.target.value) });
                  setCop("idle");
                }}
              />
            </Field>
            <Field label="Account number">
              <input
                className="inp mono"
                inputMode="numeric"
                placeholder="12345678"
                value={np.num}
                onChange={(e) => {
                  setNp({
                    ...np,
                    num: e.target.value.replace(/\D/g, "").slice(0, 8),
                  });
                  setCop("idle");
                }}
              />
            </Field>
          </div>
          {cop !== "idle" && (
            <div
              className="card"
              style={{
                background:
                  cop.outcome === "match"
                    ? "rgba(200,245,66,.1)"
                    : "var(--bg-2)",
                display: "flex",
                gap: 12,
                alignItems: "center",
              }}
            >
              <span
                className={cop.outcome === "match" ? "pos" : ""}
                style={{
                  color: cop === "checking" ? "var(--muted)" : undefined,
                }}
              >
                {cop === "checking" ? Ic.lock : COP[cop.outcome].icon}
              </span>
              <div style={{ fontSize: 14 }}>
                {cop === "checking"
                  ? "Checking the name with their bank…"
                  : COP[cop.outcome].text(np, cop.nameHeld)}
              </div>
            </div>
          )}
          {err && (
            <p className="hint err" style={{ marginTop: 8 }}>
              {err}
            </p>
          )}
        </div>
        <div className="foot">
          {cop === "idle" || cop === "checking" ? (
            <button
              className="btn"
              disabled={!npOk || cop === "checking"}
              onClick={check}
            >
              {cop === "checking" ? "Checking…" : "Check details"}
            </button>
          ) : cop.outcome === "match" ? (
            <button className="btn" onClick={() => proceed(np.name)}>
              Continue
            </button>
          ) : (
            <>
              <button className="btn" onClick={() => setCop("idle")}>
                Edit details
              </button>
              <button
                className="btn ghost"
                style={{ marginTop: 8 }}
                onClick={() =>
                  proceed(
                    cop.outcome === "close-match" ? cop.nameHeld : np.name,
                  )
                }
              >
                {cop.outcome === "close-match"
                  ? COP["close-match"].proceed(cop.nameHeld)
                  : COP[cop.outcome].proceed}
              </button>
            </>
          )}
        </div>
      </div>
    );
  if (step === 2)
    return (
      <div className="scr" data-screen-label="Pay · amount">
        <Top
          onBack={() => setStep(payee.id === "new" ? 1 : 0)}
          title={payee.name}
        />
        <div className="body" style={{ padding: "0 12px" }}>
          <div
            className="row"
            style={{ justifyContent: "center", gap: 8, marginBottom: 6 }}
          >
            <span className="chip">
              From {from.name} · {gbp(from.bal)}
            </span>
          </div>
          <Amount value={amt} onKey={key} label="Amount" />
          <div style={{ padding: "16px 8px 0" }}>
            <input
              className="inp"
              placeholder="Reference (optional)"
              value={ref}
              onChange={(e) => setRef(e.target.value.slice(0, 18))}
            />
          </div>
        </div>
        <div className="foot">
          <button
            className="btn"
            disabled={n <= 0 || n > from.bal}
            onClick={review}
          >
            {n > from.bal ? "Not enough in " + from.name : "Review"}
          </button>
        </div>
      </div>
    );
  if (step === 3)
    return (
      <div className="scr" data-screen-label="Pay · review">
        <Top onBack={() => setStep(2)} title="Review" />
        <div className="body">
          <div className="eyebrow" style={{ textAlign: "center" }}>
            Sending
          </div>
          <div className="big num">{gbp(n)}</div>
          <div className="sub" style={{ textAlign: "center" }}>
            to {payee.name}
          </div>
          <div className="card">
            <div className="kv">
              <span>From</span>
              <span>{from.name}</span>
            </div>
            <div className="kv">
              <span>Sort code</span>
              <span className="mono">{payee.sort}</span>
            </div>
            <div className="kv">
              <span>Account</span>
              <span className="mono">{payee.num}</span>
            </div>
            <div className="kv">
              <span>Reference</span>
              <span>{ref || "—"}</span>
            </div>
            <div className="kv">
              <span>Arrives</span>
              <span>Within seconds</span>
            </div>
            <div className="kv">
              <span>Fee</span>
              <span>Free</span>
            </div>
          </div>
          <p className="hint" style={{ marginTop: 16 }}>
            Only pay people you know and trust. If someone asked you to move
            money urgently, stop and call us.
          </p>
          {err && (
            <p className="hint err" style={{ marginTop: 8 }}>
              {err}
            </p>
          )}
        </div>
        <div className="foot">
          <button className="btn" onClick={() => setStep(4)}>
            {err ? "Try again" : `Send ${gbp(n)}`}
          </button>
        </div>
      </div>
    );
  if (step === 4)
    return (
      <Confirm
        label="Confirm with Face ID"
        onDone={async () => {
          try {
            await send({ from: from.id, payee, amt: n, ref }, submission);
            setStep(5);
          } catch (e) {
            setErr(e.message);
            setStep(3);
          }
        }}
      />
    );
  return (
    <div className="scr" data-screen-label="Pay · sent">
      <div className="body">
        <div className="ok" style={{ color: "var(--lime-ink)" }}>
          {Ic.check}
        </div>
        <h1 style={{ textAlign: "center" }}>Sent</h1>
        <p className="sub" style={{ textAlign: "center" }}>
          {gbp(n)} to {payee.name}. It should arrive within seconds.
        </p>
      </div>
      <div className="foot">
        <button className="btn" onClick={pop}>
          Done
        </button>
      </div>
    </div>
  );
}

export function Move({ S, params, pop, transfer }) {
  const first = params.from || S.accounts[0]?.id;
  const [fromId, setFrom] = useState(first);
  const [toId, setTo] = useState(
    () => S.accounts.find((a) => a.id !== first)?.id || null,
  );
  const [amt, key] = useAmt();
  const [step, setStep] = useState(0);
  const [err, setErr] = useState(null);
  const [submission, setSubmission] = useState(null);
  useEffect(() => {
    if (!toId || toId === fromId)
      setTo(S.accounts.find((a) => a.id !== fromId)?.id || null);
  }, [fromId]);
  const from = S.accounts.find((a) => a.id === fromId),
    to = S.accounts.find((a) => a.id === toId);
  const n = +amt || 0;
  const swap = () => {
    setFrom(toId);
    setTo(fromId);
  };
  const confirm = () => {
    setSubmission(api.idempotencyKey());
    setErr(null);
    setStep(1);
  };
  if (step === 1)
    return (
      <Confirm
        label="Moving money…"
        onDone={async () => {
          try {
            await transfer({ from: fromId, to: toId, amt: n }, submission);
            setStep(2);
          } catch (e) {
            setErr(e.message);
            setStep(0);
          }
        }}
      />
    );
  if (step === 2 && to)
    return (
      <div className="scr" data-screen-label="Move · done">
        <div className="body">
          <div className="ok" style={{ color: "var(--lime-ink)" }}>
            {Ic.check}
          </div>
          <h1 style={{ textAlign: "center" }}>Moved</h1>
          <p className="sub" style={{ textAlign: "center" }}>
            {gbp(n)} from {from.name} to {to.name}.
          </p>
        </div>
        <div className="foot">
          <button className="btn" onClick={pop}>
            Done
          </button>
        </div>
      </div>
    );
  const Pick = ({ a, lbl }) => (
    <div className="card row" style={{ padding: "12px 14px", flex: 1 }}>
      <span
        className={"acct " + a.kind}
        style={{
          width: 36,
          height: 36,
          padding: 0,
          borderRadius: 11,
          flexShrink: 0,
        }}
      ></span>
      <div style={{ minWidth: 0 }}>
        <div className="eyebrow" style={{ fontSize: 10 }}>
          {lbl}
        </div>
        <div style={{ fontSize: 15 }}>{a.name}</div>
        <div className="hint num">{gbp(a.bal)}</div>
      </div>
    </div>
  );
  return (
    <div className="scr" data-screen-label="Move money">
      <Top onBack={pop} title="Move money" close />
      <div className="body" style={{ padding: "0 12px" }}>
        {S.accounts.length < 2 ? (
          <div className="card" style={{ margin: 8 }}>
            Open a second account to move money between your own accounts.
          </div>
        ) : (
          <>
            <div
              className="row"
              style={{ gap: 8, padding: "0 8px", marginBottom: 18 }}
            >
              <Pick a={from} lbl="From" />
              <button className="ib" onClick={swap} aria-label="Swap">
                {Ic.swap}
              </button>
              {to && <Pick a={to} lbl="To" />}
            </div>
            <Amount value={amt} onKey={key} label="Amount" />
            {err && (
              <p className="hint err" style={{ margin: "8px 8px 0" }}>
                {err}
              </p>
            )}
          </>
        )}
      </div>
      {S.accounts.length >= 2 && (
        <div className="foot">
          <button
            className="btn"
            disabled={n <= 0 || n > from.bal}
            onClick={confirm}
          >
            {n > from.bal
              ? "Not enough in " + from.name
              : "Move " + (n ? gbp(n) : "money")}
          </button>
        </div>
      )}
    </div>
  );
}
