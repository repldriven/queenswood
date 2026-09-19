// Onboarding: welcome → mobile → code → about you → ID → passcode → done,
// each step a call to the bank; and signing in, for a returning
// customer. The code fills itself in, and the ID scan is an interstitial
// while the bank registers the person: both stand in for what a real
// sign-up would do off the phone.
import { useState, useEffect } from "react";
import { brand } from "./brand.js";
import { Ic, Top, Field, Pad, Err } from "./ui.jsx";
import * as api from "./api.js";

// The code the bank expects until a sender exists, typed in as SMS
// autofill would.
const SIGN_UP_CODE = import.meta.env.VITE_SIGN_UP_CODE || "123456";

const delay = (ms) => new Promise((r) => setTimeout(r, ms));

// A UK mobile as typed after the +44 box, in E.164 for the bank.
const e164 = (digits) => "+44" + digits.replace(/\D/g, "").replace(/^0/, "");
const phoneOk = (digits) => digits.replace(/\D/g, "").length >= 10;

// A date of birth as DD / MM / YYYY, formatted as it is typed, and as
// the bank takes it once it is complete and real.
const fmtDob = (s) => {
  const d = s.replace(/\D/g, "").slice(0, 8);
  return d.length > 4
    ? `${d.slice(0, 2)} / ${d.slice(2, 4)} / ${d.slice(4)}`
    : d.length > 2
      ? `${d.slice(0, 2)} / ${d.slice(2)}`
      : d;
};
const isoDob = (s) => {
  const d = s.replace(/\D/g, "");
  if (d.length !== 8) return null;
  const [day, month, year] = [+d.slice(0, 2), +d.slice(2, 4), +d.slice(4)];
  const t = new Date(Date.UTC(year, month - 1, day));
  const real =
    t.getUTCFullYear() === year &&
    t.getUTCMonth() === month - 1 &&
    t.getUTCDate() === day;
  return real && year >= 1900 && t < new Date()
    ? `${d.slice(4)}-${d.slice(2, 4)}-${d.slice(0, 2)}`
    : null;
};

const NI = /^[A-Z]{2}\d{6}[A-D]$/;

function PhoneInput({ value, onChange }) {
  return (
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
          value={value}
          onChange={(e) => onChange(e.target.value.replace(/[^\d ]/g, ""))}
          autoFocus
        />
      </div>
    </Field>
  );
}

// A returning customer: their number, then their passcode.
function SignIn({ onBack, onDone }) {
  const [step, setStep] = useState(0);
  const [phone, setPhone] = useState("");
  const [pin, setPin] = useState("");
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (pin.length !== 4) return;
    let live = true;
    setBusy(true);
    api
      .signIn(e164(phone), pin)
      .then((s) => {
        if (!live) return;
        api.session.set(s.token);
        return onDone();
      })
      .catch((e) => {
        if (!live) return;
        setErr(
          e.status === 401 ? "That number or passcode isn't right." : e.message,
        );
        setPin("");
      })
      .finally(() => live && setBusy(false));
    return () => {
      live = false;
    };
  }, [pin]);
  const key = (k) => {
    if (busy) return;
    setErr(null);
    if (k === "⌫") setPin(pin.slice(0, -1));
    else if (k !== "." && pin.length < 4) setPin(pin + k);
  };
  if (step === 1)
    return (
      <div className="scr" data-screen-label="Sign in · passcode">
        <Top onBack={() => setStep(0)} />
        <div className="body" style={{ padding: "0 12px" }}>
          <h1 style={{ textAlign: "center", padding: "0 20px" }}>
            Enter your passcode
          </h1>
          <div className="pin">
            {[0, 1, 2, 3].map((i) => (
              <i key={i} className={pin.length > i ? "f" : ""}></i>
            ))}
          </div>
          <Err center>{err}</Err>
          <Pad onKey={key} />
        </div>
      </div>
    );
  return (
    <div className="scr" data-screen-label="Sign in · mobile number">
      <Top onBack={onBack} />
      <div className="body">
        <h1>Welcome back</h1>
        <p className="sub">Your mobile number, then your passcode.</p>
        <PhoneInput value={phone} onChange={setPhone} />
      </div>
      <div className="foot">
        <button
          className="btn"
          disabled={!phoneOk(phone)}
          onClick={() => setStep(1)}
        >
          Continue
        </button>
      </div>
    </div>
  );
}

export default function Onboarding({ onDone }) {
  const [flow, setFlow] = useState("up");
  const [step, setStep] = useState(0);
  const [dir, setDir] = useState("fwd");
  const [phone, setPhone] = useState("");
  const [signUp, setSignUp] = useState(null);
  const [code, setCode] = useState("");
  const [coded, setCoded] = useState(false);
  const [me, setMe] = useState({
    first: "",
    last: "",
    dob: "",
    number: "",
    street: "",
    town: "",
    postcode: "",
    ni: "",
  });
  const [idState, setIdState] = useState("idle");
  const [verification, setVerification] = useState(null);
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const next = () => {
    setErr(null);
    setDir("fwd");
    setStep((s) => s + 1);
  };
  const back = () => {
    setErr(null);
    setDir("back");
    setStep((s) => s - 1);
  };
  // What the bank is told, or refuses, at each step.
  const sendCode = async () => {
    setBusy(true);
    setErr(null);
    try {
      setSignUp(await api.startSignUp(e164(phone)));
      setCode("");
      setCoded(false);
      next();
    } catch (e) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  };
  const details = () => ({
    "given-name": me.first.trim(),
    "family-name": me.last.trim(),
    "date-of-birth": isoDob(me.dob),
    address: {
      ...(me.number.trim() ? { "building-number": me.number.trim() } : {}),
      street: me.street.trim(),
      town: me.town.trim(),
      postcode: me.postcode.trim(),
    },
    "national-identifier": { value: me.ni },
  });
  const scan = async () => {
    setErr(null);
    setIdState("scanning");
    try {
      const [registered] = await Promise.all([
        api.registerDetails(signUp.id, details()),
        delay(2200),
      ]);
      setVerification(registered.verification);
      setIdState("done");
    } catch (e) {
      setErr(e.message);
      setIdState("idle");
    }
  };
  const finish = async () => {
    setBusy(true);
    await onDone();
    setBusy(false);
  };
  useEffect(() => {
    if (step === 2 && code.length < 6) {
      const t = setTimeout(
        () => setCode((c) => c + SIGN_UP_CODE[c.length]),
        code.length ? 140 : 900,
      );
      return () => clearTimeout(t);
    }
  }, [step, code]);
  useEffect(() => {
    if (step !== 2 || code.length !== 6) return;
    let live = true;
    api
      .verifyCode(signUp.id, code)
      .then(() => live && setCoded(true))
      .then(() => delay(500))
      .then(() => live && next())
      .catch((e) => live && setErr(e.message));
    return () => {
      live = false;
    };
  }, [code, step]);
  useEffect(() => {
    if (pin.length !== 4 || pin2.length !== 4) return;
    if (pin !== pin2) {
      const t = setTimeout(() => {
        setPin("");
        setPin2("");
      }, 300);
      return () => clearTimeout(t);
    }
    let live = true;
    api
      .choosePasscode(signUp.id, pin)
      .then((s) => {
        if (!live) return;
        api.session.set(s.token);
        next();
      })
      .catch((e) => {
        if (!live) return;
        setErr(e.message);
        setPin("");
        setPin2("");
      });
    return () => {
      live = false;
    };
  }, [pin, pin2]);
  if (flow === "in")
    return <SignIn onBack={() => setFlow("up")} onDone={onDone} />;
  const cls = "scr" + (dir === "back" ? " back" : "");
  const key = (k) => {
    const set = pin.length < 4 ? setPin : setPin2;
    const v = pin.length < 4 ? pin : pin2;
    if (k === "⌫") set(v.slice(0, -1));
    else if (k !== "." && v.length < 4) set(v + k);
  };
  const field = (k, transform = (v) => v) => ({
    value: me[k],
    onChange: (e) => setMe({ ...me, [k]: transform(e.target.value) }),
  });
  const meOk =
    me.first.trim() &&
    me.last.trim() &&
    isoDob(me.dob) &&
    me.street.trim() &&
    me.town.trim() &&
    me.postcode.trim().length >= 5 &&
    NI.test(me.ni);
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
        <button className="btn ghost" onClick={() => setFlow("in")}>
          I already have an account
        </button>
      </div>
    </div>,
    <div className={cls} key="p" data-screen-label="Mobile number">
      <Top onBack={back} />
      <div className="body">
        <h1>What's your mobile number?</h1>
        <p className="sub">We'll text you a code to confirm it's you.</p>
        <PhoneInput value={phone} onChange={setPhone} />
        <Err>{err}</Err>
        <p className="hint">
          By continuing you agree to our <a href="#">terms</a> and{" "}
          <a href="#">privacy notice</a>.
        </p>
      </div>
      <div className="foot">
        <button
          className="btn"
          disabled={!phoneOk(phone) || busy}
          onClick={sendCode}
        >
          {busy ? "Sending…" : "Send code"}
        </button>
      </div>
    </div>,
    <div className={cls} key="c" data-screen-label="Verify code">
      <Top onBack={back} />
      <div className="body">
        <h1>Enter the code we sent</h1>
        <p className="sub">
          Sent to +44 {phone || "7700 900123"}.{" "}
          <a
            href="#"
            onClick={(e) => {
              e.preventDefault();
              back();
            }}
          >
            Change number
          </a>
        </p>
        <div className="code">
          {[0, 1, 2, 3, 4, 5].map((i) => (
            <i key={i} className={i === code.length ? "on" : ""}>
              {code[i] || ""}
            </i>
          ))}
        </div>
        {err ? (
          <Err>{err}</Err>
        ) : (
          <p className="hint" style={{ marginTop: 20 }}>
            {coded
              ? "Verified"
              : code.length === 6
                ? "Checking…"
                : "Filling in automatically from Messages…"}
          </p>
        )}
      </div>
    </div>,
    <div className={cls} key="m" data-screen-label="About you">
      <Top onBack={back} />
      <div className="body">
        <h1>Tell us about you</h1>
        <p className="sub">Exactly as it appears on your ID.</p>
        <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
          <Field label="First name">
            <input className="inp" placeholder="Amara" {...field("first")} />
          </Field>
          <Field label="Last name">
            <input className="inp" placeholder="Okafor" {...field("last")} />
          </Field>
        </div>
        <Field label="Date of birth">
          <input
            className="inp mono"
            inputMode="numeric"
            placeholder="DD / MM / YYYY"
            {...field("dob", fmtDob)}
          />
        </Field>
        <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
          <Field label="House no." style={{ flex: "0 0 96px" }}>
            <input className="inp mono" placeholder="12" {...field("number")} />
          </Field>
          <Field label="Street" style={{ flex: 1 }}>
            <input
              className="inp"
              placeholder="Mare Street"
              {...field("street")}
            />
          </Field>
        </div>
        <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
          <Field label="Town">
            <input className="inp" placeholder="London" {...field("town")} />
          </Field>
          <Field label="Postcode">
            <input
              className="inp mono"
              placeholder="E8 3RH"
              {...field("postcode", (v) => v.toUpperCase())}
            />
          </Field>
        </div>
        <Field
          label="National Insurance number"
          hint="On your payslip, P60 or letters about tax."
        >
          <input
            className="inp mono"
            placeholder="QQ123456C"
            {...field("ni", (v) => v.toUpperCase().replace(/\s/g, ""))}
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
        <Err>{err}</Err>
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
            onClick={scan}
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
        <Err center>{err}</Err>
        <Pad onKey={key} />
      </div>
    </div>,
    <div className={cls} key="d" data-screen-label="Signed up">
      <div className="body">
        <div className="ok" style={{ color: "var(--lime-ink)" }}>
          {Ic.check}
        </div>
        <h1 style={{ textAlign: "center" }}>You're in, {me.first.trim()}.</h1>
        <p className="sub" style={{ textAlign: "center" }}>
          {verification === "verified"
            ? "You're verified. Open your first account from your home screen."
            : "We're checking your identity. You can open an account as soon as that's done."}
        </p>
      </div>
      <div className="foot">
        <button className="btn" disabled={busy} onClick={finish}>
          {busy ? "One moment…" : "Go to my account"}
        </button>
      </div>
    </div>,
  ];
  return screens[step];
}
