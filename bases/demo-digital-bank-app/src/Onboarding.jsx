// Onboarding: welcome → mobile → code → about you → ID → passcode → done.
// The code fills itself in and the ID scan succeeds on its own: both
// stand in for what a real sign-up would do off the phone.
import { useState, useEffect } from "react";
import { brand } from "./brand.js";
import { Ic, Top, Field, Pad } from "./ui.jsx";

export default function Onboarding({ onDone }) {
  const [step, setStep] = useState(0);
  const [dir, setDir] = useState("fwd");
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [me, setMe] = useState({ first: "", last: "", dob: "", postcode: "" });
  const [idState, setIdState] = useState("idle");
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const next = () => {
    setDir("fwd");
    setStep((s) => s + 1);
  };
  const back = () => {
    setDir("back");
    setStep((s) => s - 1);
  };
  useEffect(() => {
    if (step === 2 && code.length < 6) {
      const t = setTimeout(
        () => setCode((c) => c + "482913"[c.length]),
        code.length ? 140 : 900,
      );
      return () => clearTimeout(t);
    }
  }, [step, code]);
  useEffect(() => {
    if (step === 2 && code.length === 6) {
      const t = setTimeout(next, 500);
      return () => clearTimeout(t);
    }
  }, [code, step]);
  useEffect(() => {
    if (idState === "scanning") {
      const t = setTimeout(() => setIdState("done"), 2200);
      return () => clearTimeout(t);
    }
  }, [idState]);
  useEffect(() => {
    if (pin.length === 4 && pin2.length === 4) {
      const t = setTimeout(() => {
        if (pin === pin2) next();
        else {
          setPin("");
          setPin2("");
        }
      }, 300);
      return () => clearTimeout(t);
    }
  }, [pin, pin2]);
  const cls = "scr" + (dir === "back" ? " back" : "");
  const key = (k) => {
    const set = pin.length < 4 ? setPin : setPin2;
    const v = pin.length < 4 ? pin : pin2;
    if (k === "⌫") set(v.slice(0, -1));
    else if (k !== "." && v.length < 4) set(v + k);
  };
  const meOk =
    me.first && me.last && me.dob.length >= 8 && me.postcode.length >= 5;
  const screens = [
    <div
      className={cls}
      key="w"
      data-screen-label="Welcome"
      style={{
        background:
          "radial-gradient(120% 60% at 80% -10%,#1f3a8a 0%,var(--bg) 60%)",
      }}
    >
      <div
        className="body"
        style={{ display: "flex", flexDirection: "column" }}
      >
        <div className="logo" style={{ marginTop: 8 }}>
          <i></i>
          {brand.name}
        </div>
        <div style={{ flex: 1 }}></div>
        <h1 style={{ fontSize: 44, marginBottom: 16 }}>
          Banking that keeps up with you.
        </h1>
        <p className="sub">
          Open an account in about four minutes. All you need is your phone and
          a photo ID.
        </p>
      </div>
      <div className="foot">
        <button className="btn" onClick={next}>
          Get started
        </button>
        <button className="btn ghost" onClick={onDone}>
          I already have an account
        </button>
      </div>
    </div>,
    <div className={cls} key="p" data-screen-label="Mobile number">
      <Top onBack={back} />
      <div className="body">
        <h1>What's your mobile number?</h1>
        <p className="sub">We'll text you a code to confirm it's you.</p>
        <Field label="Mobile number">
          <div className="row" style={{ gap: 8 }}>
            <span
              className="inp"
              style={{
                width: 84,
                display: "grid",
                placeItems: "center",
                color: "var(--fg-2)",
              }}
            >
              +44
            </span>
            <input
              className="inp"
              inputMode="tel"
              placeholder="7700 900123"
              value={phone}
              onChange={(e) => setPhone(e.target.value.replace(/[^\d ]/g, ""))}
              autoFocus
            />
          </div>
        </Field>
        <p className="hint">
          By continuing you agree to our <a href="#">terms</a> and{" "}
          <a href="#">privacy notice</a>.
        </p>
      </div>
      <div className="foot">
        <button
          className="btn"
          disabled={phone.replace(/\s/g, "").length < 10}
          onClick={next}
        >
          Send code
        </button>
      </div>
    </div>,
    <div className={cls} key="c" data-screen-label="Verify code">
      <Top onBack={back} />
      <div className="body">
        <h1>Enter the code we sent</h1>
        <p className="sub">
          Sent to +44 {phone || "7700 900123"}. <a href="#">Change number</a>
        </p>
        <div className="code">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <i key={i} className={i === code.length ? "on" : ""}>
              {code[i] || ""}
            </i>
          ))}
        </div>
        <p className="hint" style={{ marginTop: 20 }}>
          {code.length === 6
            ? "Verified"
            : "Filling in automatically from Messages…"}
        </p>
      </div>
    </div>,
    <div className={cls} key="m" data-screen-label="About you">
      <Top onBack={back} />
      <div className="body">
        <h1>Tell us about you</h1>
        <p className="sub">Exactly as it appears on your ID.</p>
        <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
          <Field label="First name">
            <input
              className="inp"
              value={me.first}
              onChange={(e) => setMe({ ...me, first: e.target.value })}
              placeholder="Amara"
            />
          </Field>
          <Field label="Last name">
            <input
              className="inp"
              value={me.last}
              onChange={(e) => setMe({ ...me, last: e.target.value })}
              placeholder="Okafor"
            />
          </Field>
        </div>
        <Field label="Date of birth">
          <input
            className="inp mono"
            placeholder="DD / MM / YYYY"
            value={me.dob}
            onChange={(e) => setMe({ ...me, dob: e.target.value })}
          />
        </Field>
        <Field label="Home postcode" hint="We'll find your address from this.">
          <input
            className="inp mono"
            placeholder="E8 3RH"
            value={me.postcode}
            onChange={(e) =>
              setMe({ ...me, postcode: e.target.value.toUpperCase() })
            }
          />
        </Field>
      </div>
      <div className="foot">
        <button className="btn" disabled={!meOk} onClick={next}>
          Continue
        </button>
      </div>
    </div>,
    <div className={cls} key="i" data-screen-label="ID check">
      <Top onBack={back} />
      <div className="body">
        <h1>Confirm your identity</h1>
        <p className="sub">
          Scan your passport or driving licence, then take a quick selfie.
        </p>
        <div
          className="card"
          style={{
            height: 300,
            display: "grid",
            placeItems: "center",
            background:
              idState === "done" ? "rgba(200,245,66,.1)" : "var(--bg-2)",
            border: "1px dashed var(--line-2)",
            color: idState === "done" ? "var(--lime)" : "var(--muted)",
            position: "relative",
            overflow: "hidden",
          }}
        >
          {idState === "scanning" && <div className="scan"></div>}
          <div style={{ textAlign: "center" }}>
            {idState === "done" ? Ic.check : Ic.cam}
            <div style={{ fontSize: 14, marginTop: 10 }}>
              {idState === "idle"
                ? "camera view · passport in frame"
                : idState === "scanning"
                  ? "Reading document…"
                  : "Passport read · selfie matched"}
            </div>
          </div>
        </div>
        <div style={{ marginTop: 16 }} className="hint">
          Your documents are checked automatically and never stored on your
          phone.
        </div>
      </div>
      <div className="foot">
        {idState === "done" ? (
          <button className="btn" onClick={next}>
            Continue
          </button>
        ) : (
          <button
            className="btn"
            disabled={idState === "scanning"}
            onClick={() => setIdState("scanning")}
          >
            {idState === "scanning" ? "Scanning…" : "Scan document"}
          </button>
        )}
      </div>
    </div>,
    <div className={cls} key="pc" data-screen-label="Passcode">
      <Top onBack={back} />
      <div className="body" style={{ padding: "0 12px" }}>
        <h1 style={{ textAlign: "center", padding: "0 20px" }}>
          {pin.length < 4 ? "Choose a 4-digit passcode" : "Enter it once more"}
        </h1>
        <div className="pin">
          {[0, 1, 2, 3].map((i) => (
            <i
              key={i}
              className={(pin.length < 4 ? pin : pin2).length > i ? "f" : ""}
            ></i>
          ))}
        </div>
        <Pad onKey={key} />
      </div>
    </div>,
    <div className={cls} key="d" data-screen-label="Account ready">
      <div className="body">
        <div className="ok" style={{ color: "var(--lime-ink)" }}>
          {Ic.check}
        </div>
        <h1 style={{ textAlign: "center" }}>
          You're in, {me.first || "Amara"}.
        </h1>
        <p className="sub" style={{ textAlign: "center" }}>
          Your Everyday account is open. Your card is on its way.
        </p>
        <div className="card acct cur" style={{ cursor: "default" }}>
          <div className="eyebrow">Everyday · Current account</div>
          <div
            className="big num"
            style={{ textAlign: "left", fontSize: 36, margin: "10px 0 16px" }}
          >
            £0.00
          </div>
          <div className="row" style={{ justifyContent: "space-between" }}>
            <span className="mono">04-00-75</span>
            <span className="mono">31908240</span>
          </div>
        </div>
      </div>
      <div className="foot">
        <button className="btn" onClick={onDone}>
          Go to my account
        </button>
      </div>
    </div>,
  ];
  return screens[step];
}
