// The root: the app's state, its navigation, and the mutations the
// screens call. The state is the me read, kept under the session the
// bank minted. Navigation is a mode (loading, onboarding or app), a tab,
// and a stack of pushed flows above the tab; switching tab clears the
// stack.
import { useState, useEffect } from "react";
import { gbp } from "./ui.jsx";
import * as api from "./api.js";
import Onboarding from "./Onboarding.jsx";
import {
  Home,
  Account,
  TxnDetail,
  Activity,
  Me,
  OpenAccount,
} from "./Accounts.jsx";
import { Pay, Move } from "./Payments.jsx";

export default function App() {
  const [S, setS] = useState(null);
  const [mode, setMode] = useState(() =>
    api.session.get() ? "loading" : "onboarding",
  );
  const [tab, setTab] = useState("home");
  const [stack, setStack] = useState([]);
  const [msg, setMsg] = useState(null);
  const push = (name, params = {}) => setStack((s) => [...s, { name, params }]);
  const pop = () => setStack((s) => s.slice(0, -1));
  const go = (t) => {
    setStack([]);
    setTab(t);
  };
  const toast = (m) => {
    setMsg(m);
    setTimeout(() => setMsg(null), 1800);
  };
  // Read the home under the session held. A session the bank no longer
  // knows goes back to the welcome screen; anything else is said and
  // leaves the session for another go.
  const enter = async () => {
    try {
      setS(api.fromMe(await api.me()));
      setMode("app");
    } catch (e) {
      if (e.status === 401) api.session.clear();
      else toast(e.message);
      setMode("onboarding");
    }
  };
  useEffect(() => {
    if (mode === "loading") enter();
  }, []);
  const stamp = () => {
    const d = new Date();
    return "Today, " + d.toTimeString().slice(0, 5);
  };
  // The mutations. Each is what the backend will do instead: they keep
  // the app's own shape of the world consistent until then.
  const send = ({ from, payee, amt, ref }) =>
    setS((s) => ({
      ...s,
      accounts: s.accounts.map((a) =>
        a.id === from
          ? {
              ...a,
              bal: a.bal - amt,
              spark: [...a.spark.slice(1), a.bal - amt],
            }
          : a,
      ),
      payees:
        payee.id === "new"
          ? [
              { ...payee, id: "p" + Date.now(), last: gbp(amt) + " · today" },
              ...s.payees,
            ]
          : s.payees,
      txns: [
        {
          id: Date.now(),
          acct: from,
          who: payee.name,
          cat: "Payment",
          amt: -amt,
          when: stamp(),
          date: "Today",
          ref,
        },
        ...s.txns,
      ],
    }));
  const transfer = ({ from, to, amt }) =>
    setS((s) => {
      const F = s.accounts.find((a) => a.id === from),
        T = s.accounts.find((a) => a.id === to);
      return {
        ...s,
        accounts: s.accounts.map((a) =>
          a.id === from
            ? { ...a, bal: a.bal - amt }
            : a.id === to
              ? { ...a, bal: a.bal + amt }
              : a,
        ),
        txns: [
          {
            id: Date.now() + 1,
            acct: to,
            who: "Transfer from " + F.name,
            cat: "Saved",
            amt,
            when: stamp(),
            date: "Today",
          },
          {
            id: Date.now(),
            acct: from,
            who: "Transfer to " + T.name,
            cat: "Saved",
            amt: -amt,
            when: stamp(),
            date: "Today",
          },
          ...s.txns,
        ],
      };
    });
  const openAccount = (p, dep) =>
    setS((s) => {
      const acct = {
        id: p.id + Date.now(),
        kind: p.kind,
        name: p.name,
        type:
          p.kind === "sav"
            ? "Easy-access saver · 4.10% AER"
            : p.kind === "fix"
              ? "1 Year Fixed · 4.65% AER"
              : "Current account",
        bal: dep,
        sort: "04-00-75",
        num: "3190" + String(8200 + Math.floor(Math.random() * 700)),
        spark: [0, 0, 0, 0, 0, 0, dep],
      };
      return {
        ...s,
        accounts: [
          ...s.accounts.map((a) =>
            a.id === "cur" && dep ? { ...a, bal: a.bal - dep } : a,
          ),
          acct,
        ],
        txns: dep
          ? [
              {
                id: Date.now(),
                acct: "cur",
                who: "Transfer to " + p.name,
                cat: "Saved",
                amt: -dep,
                when: stamp(),
                date: "Today",
              },
              ...s.txns,
            ]
          : s.txns,
      };
    });
  const signOut = () => {
    api.signOut().catch(() => {});
    api.session.clear();
    setS(null);
    setMode("onboarding");
    setStack([]);
    setTab("home");
  };
  const top = stack[stack.length - 1];
  let view;
  if (mode === "loading") view = <div className="scr" />;
  else if (mode === "onboarding") view = <Onboarding onDone={enter} />;
  else if (top) {
    const P = {
      S,
      params: top.params,
      pop,
      push,
      toast,
      send,
      transfer,
      openAccount,
    };
    view = {
      account: <Account {...P} />,
      txn: <TxnDetail {...P} />,
      pay: <Pay {...P} />,
      move: <Move {...P} />,
      open: <OpenAccount {...P} />,
    }[top.name];
  } else
    view = {
      home: <Home S={S} push={push} go={go} />,
      activity: <Activity S={S} push={push} go={go} />,
      me: <Me S={S} go={go} signOut={signOut} />,
      pay: (
        <Pay S={S} params={{}} pop={() => go("home")} push={push} send={send} />
      ),
    }[tab];
  return (
    <div className="phone">
      {view}
      {msg && <div className="toast">{msg}</div>}
    </div>
  );
}
