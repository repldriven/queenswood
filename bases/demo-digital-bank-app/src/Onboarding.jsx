// Onboarding: welcome → mobile → code → about you → identity check →
// passcode → done, each step a call to the bank; and signing in, for a
// returning customer. The code fills itself in, standing in for what a
// real sign-up would do off the phone. The bank asks only for a name and
// an email: the identity check registers the person and hands them to
// the identity provider's page, where they give their date of birth,
// address, document and selfie, which the bank never sees. The page
// returns them here at `#verified` to choose a passcode.
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

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// The sign-up the app left for the identity provider's page, kept so it
// can carry on when the page returns the person.
const HANDED_OFF = "xepha.handed-off";
const PASSCODE_STEP = 5;
const returning = () => {
  if (!location.hash.startsWith("#verified")) return null;
  try {
    return JSON.parse(sessionStorage.getItem(HANDED_OFF));
  } catch {
    return null;
  }
};

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
  const [resumed] = useState(returning);
  const [flow, setFlow] = useState("up");
  const [step, setStep] = useState(resumed ? PASSCODE_STEP : 0);
  const [dir, setDir] = useState("fwd");
  const [phone, setPhone] = useState("");
  const [signUp, setSignUp] = useState(resumed ? { id: resumed.id } : null);
  const [code, setCode] = useState("");
  const [coded, setCoded] = useState(false);
  const [me, setMe] = useState({
    first: resumed?.first ?? "",
    last: "",
    email: "",
  });
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
    email: me.email.trim(),
  });
  const verify = async () => {
    setBusy(true);
    setErr(null);
    try {
      const registered = await api.registerDetails(signUp.id, details());
      setVerification(registered.verification);
      const handOff = registered["hand-off-url"];
      if (!handOff) return next();
      sessionStorage.setItem(
        HANDED_OFF,
        JSON.stringify({ id: signUp.id, first: me.first.trim() }),
      );
      location.assign(handOff);
    } catch (e) {
      setErr(e.message);
    } finally {
      setBusy(false);
    }
  };
  const finish = async () => {
    setBusy(true);
    await onDone();
    setBusy(false);
  };
  useEffect(() => {
    if (!resumed) return;
    sessionStorage.removeItem(HANDED_OFF);
    history.replaceState(null, "", location.pathname + location.search);
  }, []);
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
  const meOk = me.first.trim() && me.last.trim() && EMAIL.test(me.email.trim());
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
        <p className="sub">Your name exactly as it appears on your ID.</p>
        <div className="row" style={{ gap: 10, alignItems: "flex-start" }}>
          <Field label="First name">
            <input className="inp" placeholder="Amara" {...field("first")} />
          </Field>
          <Field label="Last name">
            <input className="inp" placeholder="Okafor" {...field("last")} />
          </Field>
        </div>
        <Field label="Email">
          <input
            className="inp"
            inputMode="email"
            placeholder="amara@example.com"
            {...field("email", (v) => v.trim())}
          />
        </Field>
      </div>
      <div className="foot">
        <button className="btn" disabled={!meOk} onClick={next}>
          Continue
        </button>
      </div>
    </div>,
    <div className={cls} key="v" data-screen-label="Identity check">
      <Top onBack={back} />
      <div className="body">
        <h1>Now, check it's you</h1>
        <p className="sub">
          Our identity partner asks for your date of birth, your address, a
          photo of your ID and a selfie. They tell us when you're verified; we
          never see any of it.
        </p>
        <div
          className="card"
          style={{
            display: "grid",
            placeItems: "center",
            padding: "36px 16px",
            background: "var(--bg-2)",
            border: "1px dashed var(--line-2)",
            color: "var(--muted)",
            textAlign: "center",
          }}
        >
          <div>
            {Ic.cam}
            <div style={{ fontSize: 14, marginTop: 10 }}>
              Takes about two minutes
            </div>
          </div>
        </div>
        <Err>{err}</Err>
      </div>
      <div className="foot">
        <button className="btn" disabled={busy} onClick={verify}>
          {busy ? "One moment…" : "Continue to identity check"}
        </button>
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
