// The load every scenario sends: a profile's steps of a fixed arrival
// rate, k6's options for them, and which step a VU is in.

import exec from "k6/execution";
import { env } from "./api.js";
import { rejectionThresholds } from "./summary.js";

function seconds(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) throw new Error(`duration ${d} is not <n>s, <n>m or <n>h`);
  return parseInt(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}

export function chosen(profiles) {
  const name = env("PROFILE", "smoke");
  const profile = profiles[name];
  if (!profile) throw new Error(`unknown PROFILE ${name}`);
  return [name, profile];
}

// An overridden rate or duration runs as one-minute steps at that rate,
// so the summary shows whether a sustained rate holds.
export function steps(profile) {
  if (!(env("RATE") || env("DURATION"))) {
    return profile.steps.map(([rate, d]) => ({ rate, seconds: seconds(d) }));
  }
  const rate = parseInt(env("RATE", profile.steps[0][0]));
  const total = seconds(env("DURATION", profile.steps[0][1]));
  const out = [];
  for (let left = total; left > 0; left -= 60) {
    out.push({ rate, seconds: Math.min(60, left) });
  }
  return out;
}

// A step reaches its rate over this many seconds, then holds it.
const RAMP = 5;

// A threshold per step, which nothing fails, so the summary carries
// each step's figures.
function stepThresholds(steps) {
  const t = {};
  steps.forEach((_, i) => {
    const tag = `{phase:load,step:${i}}`;
    t[`http_req_duration${tag}`] = ["max>=0"];
    t[`http_req_failed${tag}`] = ["rate<=1"];
    t[`payments${tag}`] = ["count>=0"];
  });
  return t;
}

export function options(scenario, steps, profile, extra) {
  const top = Math.max(...steps.map((s) => s.rate));
  const o = extra || {};
  return {
    setupTimeout: env("SETUP_TIMEOUT", "30m"),
    scenarios: {
      [scenario]: {
        executor: "ramping-arrival-rate",
        startRate: steps[0].rate,
        timeUnit: "1s",
        preAllocatedVUs: Math.max(10, top * 2),
        maxVUs: Math.min(2000, Math.max(50, top * 10)),
        stages: steps.flatMap((s) => [
          { target: s.rate, duration: `${RAMP}s` },
          { target: s.rate, duration: `${s.seconds - RAMP}s` },
        ]),
        gracefulStop: o.gracefulStop || "30s",
        tags: { phase: "load" },
      },
    },
    thresholds: Object.assign(
      {
        "http_req_failed{phase:load}": [{ threshold: "rate<0.001", abortOnFail: false }],
        "http_req_duration{phase:load}": [{ threshold: "p(99)<1000", abortOnFail: false }],
      },
      stepThresholds(steps),
      rejectionThresholds(),
      o.thresholds || {},
    ),
    summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
  };
}

export function stepNow(steps) {
  let elapsed = (Date.now() - exec.scenario.startTime) / 1000;
  for (let i = 0; i < steps.length; i++) {
    if (elapsed < steps[i].seconds) return i;
    elapsed -= steps[i].seconds;
  }
  return steps.length - 1;
}
