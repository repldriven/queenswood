// The root: the app's state, its navigation, and the mutations the
// screens call. The state is the me read, kept under the session the
// bank minted. Navigation is a mode (loading, onboarding or app), a tab,
// and a stack of pushed flows above the tab; switching tab clears the
// stack.
import { useState, useEffect } from "react";
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
  // The home read again, so what the screens show is what the platform
  // holds.
  const refresh = async () => setS(api.fromMe(await api.me()));
  // Told, not asked: while the app is open it holds the bank's event
  // stream, and each notification is said and the home read again.
  useEffect(() => {
    if (mode !== "app") return;
    return api.events((n) => {
      toast(n.headline);
      refresh().catch(() => {});
    });
  }, [mode]);
  // The mutations: each a call to the bank under the key the screen
  // minted, then the home read. Each throws the bank's refusal for the
  // screen to say.
  const send = async (payment, key) => {
    await api.pay(payment, key);
    await refresh();
  };
  const transfer = async (move, key) => {
    await api.transfer(move, key);
    await refresh();
  };
  const openAccount = async (p, dep, key) => {
    const opened = await api.openAccount({ productId: p.id, dep }, key);
    await refresh();
    return opened;
  };
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
