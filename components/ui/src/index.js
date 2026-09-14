// Public entry point of the @queenswood/ui design system.
//
//   import {
//     Logo, Wordmark, AppNav, ThemeToggle,
//     Button, Badge,
//     Sidenav, SidenavGroup, SidenavItem,
//     PageHeader, Drawer,
//     Table, Thead, Tbody, Tr, Th, Td,
//     Expander, MoneyCell, Phase,        // ← ledger tree-table
//     Field, Input, Select,
//     Card, CardHeader, CardBody, CardFooter, CodeCard,
//   } from "@queenswood/ui";
//   import { formatMoney, moneyTone, sumMinor } from "@queenswood/ui";
//   import { bootstrapTheme, setTheme, themeState, resolvedTheme }
//     from "@queenswood/ui";
//   import "@queenswood/ui/tokens.css";

export { default as Logo } from "./Logo.svelte";
export { default as Wordmark } from "./Wordmark.svelte";
export { default as AppNav } from "./AppNav.svelte";
export { default as ThemeToggle } from "./ThemeToggle.svelte";

export { default as Button } from "./Button.svelte";
export { default as Badge } from "./Badge.svelte";

export { default as Sidenav } from "./Sidenav.svelte";
export { default as SidenavGroup } from "./SidenavGroup.svelte";
export { default as SidenavItem } from "./SidenavItem.svelte";

export { default as PageHeader } from "./PageHeader.svelte";
export { default as Drawer } from "./Drawer.svelte";
export { default as Panel } from "./Panel.svelte";
export { default as PanelHead } from "./PanelHead.svelte";
export { default as Chip } from "./Chip.svelte";
export { default as ToastHost } from "./ToastHost.svelte";
export { toast, dismissToast, toastState } from "./toast.svelte.js";

export { default as Table } from "./Table.svelte";
export { default as Thead } from "./Thead.svelte";
export { default as Tbody } from "./Tbody.svelte";
export { default as Tr } from "./Tr.svelte";
export { default as Th } from "./Th.svelte";
export { default as Td } from "./Td.svelte";

// Tree-table additions (ledger accounts → constituent balances).
export { default as Expander } from "./Expander.svelte";
export { default as MoneyCell } from "./MoneyCell.svelte";
export { default as Phase } from "./Phase.svelte";
// Chart-of-accounts chips: a GL account's role (class) and accounting
// family (type). Same chip family as Phase.
export { default as GlClass } from "./GlClass.svelte";
export { default as GlType } from "./GlType.svelte";
// Trial-balance band — per-currency Σ debit / Σ credit + the balance
// assertion, summarised in cards above the ledger list.
export { default as TrialBalance } from "./TrialBalance.svelte";
export { default as TrialBalanceCard } from "./TrialBalanceCard.svelte";
export { formatMoney, formatSigned, moneyTone, sumMinor, CCY_SYMBOLS } from "./money.js";
export { activeVersion, productRows, today } from "./products.js";

// Accounts screen — a reusable search input + the cash-account status
// badge (opening / opened / closing / closed → Badge tones).
export { default as SearchField } from "./SearchField.svelte";
export { default as AccountStatusBadge } from "./AccountStatusBadge.svelte";

// Policy matrix — atoms + the composed per-domain table, plus the
// view-model helpers (DOMAINS / grouping) and bound formatting.
export { default as Effect } from "./Effect.svelte";
export { default as Bound } from "./Bound.svelte";
export { default as Improving } from "./Improving.svelte";
export { default as FilterChips } from "./FilterChips.svelte";
export { default as PolicyMatrix } from "./PolicyMatrix.svelte";
export {
  DOMAINS,
  DOMAIN_ORDER,
  GROUP_ORDER,
  CATEGORY_TONE,
  groupByDomain,
  sectionRows,
} from "./policy.js";
// CCY_SYMBOLS already exported from money.js (same constant); don't re-export.
export { formatAggregate, boundOp, boundWindow, WINDOW_LABEL } from "./bounds.js";

// Scheduler (Jobs) — the run-status badge, the task pipeline + kebab
// menu, plus the schedule/format view-model helpers. The page itself
// lives in console.
export { default as JobStatusBadge } from "./JobStatusBadge.svelte";
export { default as JobKindChip } from "./JobKindChip.svelte";
export { default as TaskPipeline } from "./TaskPipeline.svelte";
export { default as Menu } from "./Menu.svelte";
export {
  hhmm,
  humanSchedule,
  cronOf,
  nextRunAt,
  nextRuns,
  fmtDur,
  fmtAbs,
  fmtRel,
  lastOutcome,
  fmtElapsed,
  pipelineSteps,
  runPipelineSteps,
  minutesFromHHMM,
  isLastDay,
} from "./jobs.js";

// Scenario sandbox — the Scenarios screen's narrative primitives. The
// page (the run engine + scene data) lives in console.
export { default as ProgressSpine } from "./ProgressSpine.svelte";
export { default as BankStateBand } from "./BankStateBand.svelte";
export { default as SceneCard } from "./SceneCard.svelte";
export { default as RawCalls } from "./RawCalls.svelte";

// Cash-account migrations — the product/version choosers the edit
// drawer is built from, the status badge, and the screen's view-model
// helpers. The page itself lives in console.
export { default as ProductPicker } from "./ProductPicker.svelte";
export { default as VersionList } from "./VersionList.svelte";
export { default as MigrationStatusBadge } from "./MigrationStatusBadge.svelte";
export {
  shortEnum,
  MIGRATION_TONE,
  RUN_TONE,
  OUTCOME_TONE,
  INELIGIBILITY_ORDER,
  INELIGIBILITY_LABEL,
  MIGRATION_GUARDS,
  fmtDay,
  fmtStamp,
  todayIso,
  daysBetween,
  fmtRate,
  fmtRateDelta,
  fmtDuration,
} from "./migrations.js";

// People and access — the role pill, the tabs, tags beside a name, the
// refusal callout, the reason textarea and the one-time secret panel,
// plus the ladder and formatting helpers. The page itself lives in
// console.
export { default as RolePill } from "./RolePill.svelte";
export { default as Tabs } from "./Tabs.svelte";
export { default as Tag } from "./Tag.svelte";
export { default as Callout } from "./Callout.svelte";
export { default as Textarea } from "./Textarea.svelte";
export { default as TokenBox } from "./TokenBox.svelte";
export {
  ROLES,
  ROLE_RANK,
  ROLE_BLURB,
  accessEnum,
  grantableBy,
  canManagePeople,
  canActOn,
  isLastOwner,
  EVENT_LABEL,
  EVENT_FAMILY,
  fmtUtcDate,
  fmtUtcDateTime,
  fmtFromNow,
  expiryUrgency,
} from "./access.js";

export { default as Field } from "./Field.svelte";
export { default as Input } from "./Input.svelte";
export { default as Select } from "./Select.svelte";

export { default as Card } from "./Card.svelte";
export { default as CardHeader } from "./CardHeader.svelte";
export { default as CardBody } from "./CardBody.svelte";
export { default as CardFooter } from "./CardFooter.svelte";
export { default as CodeCard } from "./CodeCard.svelte";

export {
  themeState,
  setTheme,
  bootstrapTheme,
  resolvedTheme,
} from "./theme.svelte.js";
