// Types for @queenswood/ui as documentation. The components are Svelte 5
// components, not React: mount one with Queenswood.mount or
// Queenswood.render, or import it in a Svelte app. A `Snippet` prop is a
// Svelte snippet; from plain JavaScript make one with Queenswood.snippet(html).
import type { Component, Snippet } from "svelte";

// ---- Brand ----
export interface LogoProps {
  /** A eight-tree forest · B adds jewels · C monoline · D four trees, five teeth · E sealed in rings. */
  variant?: "A" | "B" | "C" | "D" | "E";
  /** Rendered width and height in px. */
  size?: number;
  /** Prefix for the SVG gradient ids. Give each instance on a page its own. */
  idPrefix?: string;
}
export interface WordmarkProps {
  /** grotesk: QUEENS (300) + WOOD (500), 0.18em tracking, product chrome. serif: Queenswood, Cormorant Garamond 500. */
  variant?: "grotesk" | "serif";
  /** Font size in px. */
  size?: number;
  /** Any CSS colour. Defaults to the inherited text colour. */
  color?: string;
}

// ---- Chrome ----
export interface AppNavProps {
  /** The signed-in person. `avatar-url` and `name` are both optional. */
  user?: { name?: string; "avatar-url"?: string } | null;
  onSignOut?: () => void;
}
export interface ThemeToggleProps {}
export interface SandboxBannerProps {}
export interface SidenavProps {
  /** Sticky offset from the top of the viewport, in px: the height of the bar above it. */
  top?: number;
  expandedWidth?: number;
  collapsedWidth?: number;
  /** SidenavGroup elements. */
  children?: Snippet;
}
export interface SidenavGroupProps {
  /** Mono caps heading, hidden when the rail is collapsed. */
  title?: string;
  /** SidenavItem elements. */
  children?: Snippet;
}
export interface SidenavItemProps {
  /** Renders a link. Omit it and pass `onclick` for a button. */
  href?: string;
  /** Marks the active page: left stripe and tinted background. */
  current?: boolean;
  /** Native tooltip, the only label once the rail is icon-only. */
  title?: string;
  /** A 16×16 stroked SVG. */
  icon?: Snippet;
  /** The label. */
  children?: Snippet;
  onclick?: (e: MouseEvent) => void;
}
export interface PageHeaderProps {
  /** Mono caps in gold-deep: the organisation or context. */
  kicker?: string;
  /** The page name, grotesk 22/500. */
  title?: string;
  /** One line of explanation. */
  sub?: string;
  /** Sits beside the title, e.g. a Badge. */
  titleAside?: Snippet;
  /** Buttons, right-aligned. */
  actions?: Snippet;
}
export interface TabsProps {
  tabs?: { id: string; label: string; count?: number | string }[];
  /** The selected tab's id. Bindable. */
  value?: string;
  /** The tablist's accessible name. */
  label?: string;
}

// ---- Actions ----
export interface ButtonProps {
  /** Intent, not colour. primary: one per surface. brand: gold, publish. line: secondary. ghost: tertiary. danger: destructive. */
  variant?: "primary" | "brand" | "line" | "ghost" | "danger";
  /** 26px, 36px or 44px tall. */
  size?: "sm" | "md" | "lg";
  /** Full width, label centred. */
  block?: boolean;
  /** With variant="danger": a filled, irreversible destructive action. */
  solid?: boolean;
  type?: "button" | "submit" | "reset";
  disabled?: boolean;
  onclick?: (e: MouseEvent) => void;
  /** The label, optionally led by a 14px SVG. */
  children?: Snippet;
}
export interface ChipProps {
  /** Published as aria-pressed. */
  pressed?: boolean;
  /** A trailing figure. A number is formatted en-GB. */
  count?: number | string;
  disabled?: boolean;
  onclick?: (e: MouseEvent) => void;
  children?: Snippet;
}
export interface MenuItem {
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  /** A rule above this item. */
  divider?: boolean;
  /** Rust text, for a destructive action. */
  danger?: boolean;
}
export interface MenuProps {
  /** The trigger's getBoundingClientRect(). */
  anchor: DOMRect;
  items?: MenuItem[];
  onClose?: () => void;
}

// ---- Forms ----
export interface FieldProps {
  label?: string;
  /** Text, or a snippet for richer markup. */
  hint?: string | Snippet;
  /** The id of the control inside. */
  htmlFor?: string;
  /** One control: Input, Select, Textarea, SearchField. */
  children?: Snippet;
}
export interface InputProps {
  type?: string;
  /** Bindable. */
  value?: string | number;
  /** A unit inside the right edge, not editable: "bps", "%", "GBP". */
  affix?: string;
  id?: string;
  placeholder?: string;
  disabled?: boolean;
}
export interface SelectProps {
  /** Bindable. */
  value?: string;
  /** <option> elements. */
  children?: Snippet;
  id?: string;
  disabled?: boolean;
}
export interface TextareaProps {
  /** Bindable. */
  value?: string;
  /** The counter turns rust at this length. */
  maxlength?: number;
  id?: string;
  placeholder?: string;
}
export interface CheckboxProps {
  /** "mixed" shows a dash, for a group whose items are partly ticked. */
  checked?: boolean | "mixed";
  title?: string;
  onclick?: (e: MouseEvent) => void;
  /** The label. */
  children?: Snippet;
  /** At the far end of the row, e.g. "1/3". */
  aside?: Snippet;
}
export interface SearchFieldProps {
  /** Bindable. */
  value?: string;
  placeholder?: string;
  /** 40px, or the 34px compact size for panel heads. */
  size?: "md" | "sm";
  ariaLabel?: string;
  onclear?: () => void;
  onkeydown?: (e: KeyboardEvent) => void;
}
export interface TokenBoxProps {
  label?: string;
  /** The part that is not secret. */
  base?: string;
  /** Rendered in gold after `base`. The two are copied as one string. */
  secret?: string;
}

// ---- Status ----
export type BadgeTone =
  | "draft" | "published" | "archived" | "pending" | "rejected" | "neutral"
  | "standard" | "restricted" | "emergency"
  | "succeeded" | "failed" | "running" | "scheduled"
  | "accepted" | "expired" | "withdrawn" | "declined";
export interface BadgeProps {
  tone?: BadgeTone;
  /** One or two words. Rendered lowercase whatever the case passed. */
  children?: Snippet;
}
export interface AccountStatusBadgeProps {
  status?: "opening" | "opened" | "closing" | "closed" | string;
}
export interface JobStatusBadgeProps {
  outcome?: "scheduled" | "running" | "completed" | "failed";
}
export interface MigrationStatusBadgeProps {
  status: string;
  /** Which lifecycle `status` belongs to. */
  kind?: "migration" | "run" | "outcome";
}
export interface RolePillProps {
  role: "owner" | "admin" | "developer" | "viewer";
}
export interface TagProps {
  tone?: "you" | "founder" | "operator" | "bank" | "neutral";
  children?: Snippet;
}
export interface PhaseProps {
  /** "posted" is pine. Anything else reads as pending, violet. */
  phase?: "posted" | "pending-incoming" | "pending-outgoing" | string;
}
export interface HttpStatusProps {
  status: number;
}
export interface GlClassProps {
  value?: "control" | "summary" | "detail";
}
export interface GlTypeProps {
  value?: "asset" | "expense" | "liability" | "equity" | "income";
}
export interface JobKindChipProps {
  kind?: "user" | "system";
}
export interface RemovableChipProps {
  onremove?: () => void;
  /** The × button's accessible name. */
  removeLabel?: string;
  children?: Snippet;
}

// ---- Feedback ----
export interface CalloutProps {
  /** danger: an API refusal. warn: a consequence to read before confirming. info: context. */
  tone?: "danger" | "warn" | "info";
  title?: string;
  /** A mono line under the body: the problem's status and type. */
  type?: string;
  children?: Snippet;
}
export interface ToastHostProps {}
/** Publish a toast. A second call replaces the first and restarts its 2.6s timer. */
export declare function toast(message: string, detail?: string): void;
export declare function dismissToast(): void;

// ---- Surfaces ----
export interface CardProps {
  variant?: "default" | "feature" | "sunk" | "outline";
  /** 16, 24, 36px or none. */
  padding?: "compact" | "default" | "spacious" | "none";
  /** Renders the whole card as a link. */
  href?: string;
  /** Renders the whole card as a button. */
  onclick?: (e: MouseEvent) => void;
  /** Hover lift with no action. */
  clickable?: boolean;
  /** The element to render. Defaults to a, button or article. */
  as?: string;
  /** CardHeader, CardBody, CardFooter. */
  children?: Snippet;
}
export interface CardHeaderProps {
  /** Mono caps, 0.16em tracking. */
  kicker?: string;
  /** Serif 22/500. */
  title?: string;
  titleAs?: string;
  children?: Snippet;
  actions?: Snippet;
}
export interface CardBodyProps { children?: Snippet }
export interface CardFooterProps { children?: Snippet }
export interface CodeCardProps {
  /** Shown in the title bar beside the three dots. */
  filename?: string;
  /** A <pre>, tokens wrapped in .syn-* spans. */
  children?: Snippet;
}
export interface PanelProps {
  /** none: the content owns its edges. sm 14×20, md 20×24, lg 22×24. */
  pad?: "none" | "sm" | "md" | "lg";
  children?: Snippet;
}
export interface PanelHeadProps {
  title: string;
  /** A number reads as a count, a string as a qualifier. */
  count?: number | string;
  /** Mono, right-hand. Ignored when `actions` is given. */
  note?: string;
  actions?: Snippet;
  children?: Snippet;
}
export interface DrawerProps {
  open?: boolean;
  /** Called on Esc, a click on the scrim and the close button. */
  onClose?: () => void;
  kicker?: string;
  title?: string;
  sub?: string;
  /** The body: usually Fields. */
  children?: Snippet;
  /** Pinned to the bottom: usually one block Button. */
  footer?: Snippet;
  /** Width in px, capped at 92vw. */
  width?: number;
  /** Accessible name, where there is no title. */
  label?: string;
  /** Sets the title in the mono face, for an identifier. */
  mono?: boolean;
}

// ---- Data ----
export interface TableProps {
  /** An expandable tree-table: account rows over the balances that make them up. */
  tree?: boolean;
  /** No border or radius, scrolls sideways: for a table filling a Panel. */
  flush?: boolean;
  /** Thead and Tbody. */
  children?: Snippet;
}
export interface TheadProps { children?: Snippet }
export interface TbodyProps { children?: Snippet }
export interface TrProps {
  /** A parent row: role=button, focusable. Wire onclick and an Enter/Space onkeydown. */
  expandable?: boolean;
  expanded?: boolean;
  /** A child row: sunk background, indented. */
  balance?: boolean;
  /** The final child row under a parent. */
  last?: boolean;
  onclick?: (e: MouseEvent) => void;
  onkeydown?: (e: KeyboardEvent) => void;
  children?: Snippet;
}
export interface ThProps {
  align?: "right" | "center";
  children?: Snippet;
}
export interface TdProps {
  /** Mono, 12px: ids and codes. */
  mono?: boolean;
  /** fg-muted: secondary information. */
  muted?: boolean;
  /** fg, weight 500: the row's primary value. */
  emphasized?: boolean;
  /** Tabular figures. */
  tabular?: boolean;
  /** The narrow leftmost cell holding an Expander. */
  expander?: boolean;
  /** A tree row's indented, mono address cell. */
  addr?: boolean;
  align?: "right" | "center";
  children?: Snippet;
}
export interface ExpanderProps {}
export interface MoneyCellProps {
  /** Minor units: pence, cents. */
  minor: number;
  ccy?: "GBP" | "EUR" | "USD" | string;
  /** A muted subline, e.g. "3 balances". */
  meta?: string;
  /** For a total over lighter child rows. */
  emphasized?: boolean;
}
export interface TrialBalanceBlock {
  ccy: string;
  sym: string;
  name: string;
  accounts: number;
  debitMinor: number;
  creditMinor: number;
}
export interface TrialBalanceProps {
  blocks?: TrialBalanceBlock[];
  /** e.g. "09:42 UTC". */
  asOf?: string | null;
}
export interface TrialBalanceCardProps extends TrialBalanceBlock {}
export interface ChangesetTableProps {
  /** Either side may be absent. No changeset at all says the act changed no stored fields. */
  changeset?: { before?: Record<string, unknown>; after?: Record<string, unknown> };
}

// ---- Policy ----
export interface EffectProps {
  effect?: "allow" | "deny";
}
export interface Aggregate {
  type: "count" | "amount";
  /** For a count. */
  value?: number;
  /** For an amount, in minor units. */
  minor?: number;
  ccy?: string;
  window: "instant" | "daily" | string;
}
export type BoundValue =
  | { kind: "max" | "min"; aggregate: Aggregate }
  | { kind: "range"; min: Aggregate; max: Aggregate };
export interface BoundProps {
  bound: BoundValue;
}
export interface ImprovingProps {}
export interface PolicyFilter { key: string; value: string }
export interface FilterChipsProps {
  /** Empty renders a muted "any". */
  filters?: PolicyFilter[];
  /** Chips shown before the rest collapse into "+N more". */
  max?: number;
}
export interface PolicyCapability {
  effect: "allow" | "deny";
  domain: string;
  action: string;
  reason?: string;
  filters: PolicyFilter[];
  origin?: { tier?: string; name?: string; policyId?: string };
}
export interface PolicyLimit {
  domain: string;
  bound: BoundValue;
  reason?: string;
  filters: PolicyFilter[];
  allow?: "improving" | null;
  origin?: { tier?: string; name?: string; policyId?: string };
}
export interface PolicyMatrixProps {
  policy: { policyId: string; name: string; capabilities: PolicyCapability[]; limits: PolicyLimit[] };
  /** Also list the domains the policy does not mention, muted. */
  showUngoverned?: boolean;
  /** Case-insensitive filter over domain, action, reason and filter text. */
  query?: string;
}

// ---- Workflow ----
export interface PipelineStep {
  name: string;
  status: "ok" | "failed" | "running" | "skipped" | "pending" | "exception";
  /** What the task did, replacing the status word. */
  detail?: string;
  /** A second line in the danger tone. */
  alert?: string;
}
export interface TaskPipelineProps {
  steps?: PipelineStep[];
  /** Smaller nodes, for use inside a list. */
  dense?: boolean;
}
export interface ProgressSpineProps {
  title?: string;
  steps?: { num: string; label: string; status: "locked" | "ready" | "running" | "done" }[];
  /** Follows "N of M". */
  progressLabel?: string;
  onJump?: (index: number) => void;
}
export interface BankStateBandProps {
  cells?: { figure: number | string; unit?: string; label: string; muted?: boolean }[];
  /** Tints the attention cell's icon tile. */
  attentionTone?: "idle" | "good" | "gold";
  icon?: Snippet;
  title?: Snippet;
  sub?: Snippet;
  action?: Snippet;
}
export interface SceneCardProps {
  num: string;
  title: string;
  story: string;
  status?: "locked" | "ready" | "running" | "done";
  /** Where the scene's result shows, e.g. "ledger". */
  payoffLabel?: string;
  open?: boolean;
  elId?: string;
  onToggle?: () => void;
  onRun?: () => void;
  /** Revealed when open. */
  body?: Snippet;
}
export interface RawCallsProps {
  rows?: { method: string; path: string; tag: string }[];
  /** The scenario ids the steps are drawn from. */
  backing?: string[];
  /** Hidden until true. */
  show?: boolean;
}

// ---- Pickers and filters ----
export interface PickerProduct {
  id: string;
  name: string;
  type: string;
  publishedCount?: number;
  /** Omitted from the row when undefined. */
  accountCount?: number;
}
export interface ProductPickerProps {
  products?: PickerProduct[];
  /** The chosen product's id. */
  value?: string | null;
  open?: boolean;
  onselect?: (product: PickerProduct) => void;
  ontoggle?: () => void;
  /** Says why the list is shorter than expected. */
  hiddenNote?: string;
  label?: string;
}
export interface PickerVersion {
  id: string;
  number: number;
  meta?: string;
  from?: string | null;
  right?: string;
  published?: boolean;
}
export interface VersionListProps {
  /** multi: checkboxes, `selected` an array of ids. single: radios, `selected` one id. */
  mode?: "multi" | "single";
  versions?: PickerVersion[];
  selected?: string[] | string | null;
  onchange?: (next: string[] | string) => void;
  title?: string;
  action?: { label: string; onclick: () => void };
  /** The radio group's name, in single mode. */
  name?: string;
}
export type TimeRange = null | { preset: "1h" | "24h" | "7d" } | { from: number | null; to: number | null };
export interface TimeRangeFilterProps {
  /** Epoch milliseconds, either end open. */
  range: TimeRange;
  onChange: (next: TimeRange) => void;
  /** Shown under the custom inputs. */
  note?: string;
  /** Bounds the custom inputs from below, epoch milliseconds. */
  oldest?: number;
}
export interface MemberFilterProps {
  members?: { "user-id": string; name?: string; email: string }[];
  /** User ids. Nothing ticked means everyone. */
  selected?: string[];
  onChange: (userIds: string[]) => void;
  label?: string;
}

// ---- The global ----
type C<P> = Component<P>;
declare global {
  interface Window {
    Queenswood: {
      Logo: C<LogoProps>; Wordmark: C<WordmarkProps>;
      AppNav: C<AppNavProps>; ThemeToggle: C<ThemeToggleProps>; SandboxBanner: C<SandboxBannerProps>;
      Sidenav: C<SidenavProps>; SidenavGroup: C<SidenavGroupProps>; SidenavItem: C<SidenavItemProps>;
      PageHeader: C<PageHeaderProps>; Tabs: C<TabsProps>;
      Button: C<ButtonProps>; Chip: C<ChipProps>; Menu: C<MenuProps>;
      Field: C<FieldProps>; Input: C<InputProps>; Select: C<SelectProps>; Textarea: C<TextareaProps>;
      Checkbox: C<CheckboxProps>; SearchField: C<SearchFieldProps>; TokenBox: C<TokenBoxProps>;
      Badge: C<BadgeProps>; AccountStatusBadge: C<AccountStatusBadgeProps>; JobStatusBadge: C<JobStatusBadgeProps>;
      MigrationStatusBadge: C<MigrationStatusBadgeProps>; RolePill: C<RolePillProps>; Tag: C<TagProps>;
      Phase: C<PhaseProps>; HttpStatus: C<HttpStatusProps>; GlClass: C<GlClassProps>; GlType: C<GlTypeProps>;
      JobKindChip: C<JobKindChipProps>; RemovableChip: C<RemovableChipProps>;
      Callout: C<CalloutProps>; ToastHost: C<ToastHostProps>;
      Card: C<CardProps>; CardHeader: C<CardHeaderProps>; CardBody: C<CardBodyProps>; CardFooter: C<CardFooterProps>;
      CodeCard: C<CodeCardProps>; Panel: C<PanelProps>; PanelHead: C<PanelHeadProps>; Drawer: C<DrawerProps>;
      Table: C<TableProps>; Thead: C<TheadProps>; Tbody: C<TbodyProps>; Tr: C<TrProps>; Th: C<ThProps>; Td: C<TdProps>;
      Expander: C<ExpanderProps>; MoneyCell: C<MoneyCellProps>;
      TrialBalance: C<TrialBalanceProps>; TrialBalanceCard: C<TrialBalanceCardProps>; ChangesetTable: C<ChangesetTableProps>;
      Effect: C<EffectProps>; Bound: C<BoundProps>; Improving: C<ImprovingProps>; FilterChips: C<FilterChipsProps>;
      PolicyMatrix: C<PolicyMatrixProps>;
      TaskPipeline: C<TaskPipelineProps>; ProgressSpine: C<ProgressSpineProps>; BankStateBand: C<BankStateBandProps>;
      SceneCard: C<SceneCardProps>; RawCalls: C<RawCallsProps>;
      ProductPicker: C<ProductPickerProps>; VersionList: C<VersionListProps>;
      TimeRangeFilter: C<TimeRangeFilterProps>; MemberFilter: C<MemberFilterProps>;
      toast: typeof toast; dismissToast: typeof dismissToast;
      /** Svelte's mount: mount(Component, { target, props }). */
      mount: (component: Component<any>, options: { target: Element; props?: Record<string, unknown> }) => unknown;
      unmount: (instance: unknown) => void;
      /** mount with a string `children` taken as text. */
      render: (component: Component<any>, target: Element, props?: Record<string, unknown>) => unknown;
      /** A snippet from an HTML string, for `children` or a named snippet prop. */
      snippet: (html: string) => Snippet;
      setTheme: (pref: "auto" | "light" | "dark") => void;
      bootstrapTheme: () => void;
      resolvedTheme: () => "light" | "dark";
      formatMoney: (minor: number, ccy?: string) => string;
      /** One compiled demo per component, as the system's own previews mount them. */
      previews: Record<string, Component<{}>>;
    };
  }
}
