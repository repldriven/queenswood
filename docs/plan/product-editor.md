# Plan: the product version editor

A brief for the session that replaces the console's product drawer with
a page that states all of a cash account product version's terms. It
says what exists, what the page shows, how each field maps to the API,
what the backend lacks, and what is still to decide.

The design is two artboards on a Claude design canvas, copied beside
this file as reference markup:

- [product-editor/easy-saver.html](product-editor/easy-saver.html):
  draft v2 of a savings product. A banded rate for 12 months, then a
  reversion rate, a withdrawal limit, a cap on money in and a welcome
  reward.
- [product-editor/regular-saver.html](product-editor/regular-saver.html):
  draft v1 of a regular saver. A flat 6.31% fixed rate, £300 a month
  in, and the account matures after 12 months.

They are design-canvas sources (`.dc.html`), not console code: read
them for layout, copy and the states shown, and build with
`@queenswood/ui` components. The colours, type and spacing are the
Queenswood design system's tokens.

## What exists

- **The drawer.** `bases/console/src/lib/ProductDrawer.svelte` takes a
  type, a currency, a name, `interest-rate-bps` and an effective window,
  in three modes: `create`, `edit-draft` and `new-version`.
  `Products.svelte` opens it and refetches when it saves.
- **The API.** `create_cash_account_product`,
  `open_cash_account_product_draft` and
  `update_cash_account_product_draft` in `lib/api.mjs`. The domain's
  `product-fields` in `cash_account_product/domain.clj` accepts
  `interest-rate-bps` (stored as flat `interest-terms`),
  `opening-reward`, `payment-limits`, `min-balance`, `max-balance`,
  `effective-from` and `effective-to`.
- **The record.** `CashAccountProduct` carries `InterestTerms` (an
  accrual grid of bands and periods, and a payment), `reward_terms` and
  `limits`. See [cash-account-products](../tdd/cash-account-products.md#interest-terms)
  and [product-limits](../tdd/product-limits.md).
- **The API** takes and returns `interest-terms`, validated by the
  domain's `interest-fields`, and returns the currency's `day-count`
  and `business-day-convention` beside them.
- **Not built.** `term_months` and the payout account are only in the
  TDD. Nothing restricts where withdrawals go.

## The page

A route of its own, `#/products/:product-id/versions/:version-id`, as
`MigrationDetail` is for a migration. The drawer is too narrow for the
terms. Creating a product is an open decision (below).

**Header.** `PageHeader` with the bank's name as kicker, the product's
name, a `draft` badge, the product id and version in mono, and one
line: "Publishing fixes this version's terms. Accounts already open
stay on the version they opened on until a migration moves them."
Actions: Discard draft (danger), Save draft (line), Publish vN (brand).
Breadcrumb: Products / name / vN.

**Started from an earlier version.** In `new-version` mode a sunk
banner says which version the draft started from and when it was
published, with a count of changes. Each changed field carries a small
mono marker of the old value, such as `v1 4.50%`.

**Layout.** Sections stacked in a main column, and a sticky rail on the
right, which stacks below on a narrow screen. Each section is a `Panel`
with a title and one line of explanation.

### Product

One row: **Type**, **Name** (the widest), **Currency**.

- Type lists the templates by product type: savings, current, term
  deposit. It sends the type's `template-id`, as the drawer does. It is
  disabled once a version is published.
- Currency lists the type's `allowed-currencies`.
- Show nothing else of the template: no ISO type, books, balances or
  payment schemes.
- Hint: "The name is what end customers see; each version can change
  it."

### Interest

A "Pays interest" checkbox in the panel head. Off sends no
`interest-terms`.

Two parts, each with its one line of explanation.

**Accrual.** "The rate each band of the balance earns in each period.
Interest accrues daily on the end-of-day balance." A grid:

- Each column is a band, headed by its lowest balance: £0.00, then
  whatever the person types. "Add band" adds a column, up to 10.
- Each row is a period, headed by its start: "Opening", then "12 months
  after opening", or a date for a fixed schedule. "Add period" adds a
  row, up to 12. A unit select (days, weeks, months) beside the grid
  sets the unit every relative row counts in.
- Each cell is a rate a year, `%` affix, two decimals, sent in basis
  points: 5.00% is 500.
- A flat rate is one cell. For a regular saver the hint under the grid
  shows the AER, worked out from the first cell and the payment
  frequency: 6.31% paid monthly is 6.50% AER.
- There is no choice of banding. Always send `marginal`.

**Payment.** "When accrued interest is paid into the account." One row
that reads as a sentence: `Interest paid [Monthly] on [each monthly
anniversary of opening]`.

- How often: Daily, Weekly, Monthly, Every 3 months, Every 6 months,
  Annually, When the account closes.
- On: the anniversary of opening, labelled for the frequency ("each
  monthly anniversary of opening", "each anniversary of opening"), the
  1st to the 28th, or the last day of the month, sent as 31. Hidden for
  Daily and When the account closes.
- Starting in: a month, shown only for a payment less often than
  monthly on a set day.
- The day count and business day convention are the currency's, shown
  as text: "Actual/365 · modified following".

### Term

How long the version's terms run for each account, and what happens
when they end.

- **Runs**: a segmented control, No term | Months from opening | Until
  a fixed date, with a months field (1 to 120) or a date beside it.
- **When the term ends**: two option cards.
  - "The rate changes": the account stays open and the whole balance
    earns the reversion rate, a `%` field in the card.
  - "The account matures": the last interest is paid, the balance moves
    to the account the customer named when opening, and the account
    closes.
- Hint: "A migration can't move accounts onto or off a version that
  matures."

This replaces both a "rate term" in the interest section and a separate
maturity section, which read as the same thing twice.

### Limits

- **Lowest balance** and **Highest balance**, in £. Hints: "Below zero
  is an arranged overdraft." and "Must be above the lowest balance."
- **Payment limits**: a table with the columns Direction (In, Out),
  Measure (Amount, Count), At most, Each (Payment, Day, Week, Month,
  Quarter, Year), Counted from (Opening, or Date with a day and month)
  and Reason. "Add payment limit" adds a row. For Payment and Day,
  Counted from reads "Each business day" and takes no anchor. A blank
  reason shows the default reason the domain gives as a placeholder.
- **Withdrawals only to the customer's own accounts**: a checkbox the
  regular saver needs. It has no backend (below), so leave it out until
  that is decided.

### Rewards

A "Welcome reward" checkbox in the panel head, and an amount in £. Hint:
"Paid when the account opens. Must be more than £0.00."

### Availability

Effective from and Effective to, which is optional. Sub line: "The end
date stops openings, not interest on accounts already open."

### The rail

- **Section index**: one link per section, each with a mono summary,
  such as `GBP · savings`, `6.31% fixed`, `12 months · matures` or
  `£300 a month`.
- **Rate schedule**: a small bar chart. Blocks of the rate's bands
  across the term, then the reversion rate, or a dashed "matures"
  block.
- **Customer terms**: the version written out in plain English, built
  from the form state, one entry per section. For the regular saver:
  - Interest: "6.31% a year gross (6.50% AER), fixed on the whole
    balance. Earned daily and paid monthly on the day the account
    opened."
  - Term: "12 months after opening, the balance and interest move to
    the account the customer named, and this account closes."
  - Limits: "Up to £300.00 paid in each month, counted from the
    opening day. The balance can't go below £0.00."

## What the page sends

The Easy Saver, which runs 12 months from opening and then pays its
reversion rate:

```json
{
  "name": "Easy Saver",
  "template-id": "<the savings template>",
  "currency": "GBP",
  "interest-terms": {
    "accrual": {
      "basis": "relative",
      "banding": "marginal",
      "bands": [0, 500000],
      "unit": "month",
      "periods": [
        {"after": 0, "rates-bps": [500, 0]},
        {"after": 12, "rates-bps": [150, 150]}
      ]
    },
    "payment": {"kind": "account-day", "unit": "month", "every": 1}
  },
  "min-balance": 0,
  "payment-limits": [
    {"direction": "out", "measure": "count", "max": 3, "period": "year",
     "anchor": "calendar", "starts-month": 4, "starts-day-of-month": 6,
     "reason": "Three withdrawals a tax year"},
    {"direction": "in", "measure": "amount", "max": 5000000, "period": "day"}
  ],
  "opening-reward": {"amount": 2500},
  "effective-from": "2026-11-01"
}
```

The Regular Saver, which matures after 12 months:

```json
{
  "name": "Regular Saver",
  "template-id": "<the savings template>",
  "currency": "GBP",
  "interest-terms": {
    "accrual": {
      "basis": "relative",
      "banding": "marginal",
      "bands": [0],
      "unit": "month",
      "periods": [{"after": 0, "rates-bps": [631]}]
    },
    "payment": {"kind": "account-day", "unit": "month", "every": 1}
  },
  "term-months": 12,
  "min-balance": 0,
  "payment-limits": [
    {"direction": "in", "measure": "amount", "max": 30000, "period": "month",
     "anchor": "account", "reason": "Up to £300 a month"}
  ],
  "effective-from": "2026-11-01"
}
```

How the term maps to the record:

| The form | `interest-terms` | Other fields |
|---|---|---|
| No term | the grid as drawn | none |
| Months from opening, the rate changes | `basis: relative`; a last row `after` the term at the reversion rate in every band | none |
| Until a fixed date, the rate changes | `basis: fixed`; a last row `from-on` the date | none |
| Months from opening, the account matures | the grid as drawn | `term-months` |
| Until a fixed date, the account matures | the grid as drawn | no field yet |

## Backend work, in order

1. **Done:** the API takes and returns `interest-terms` as a grid and a
   payment, refusing `:cash-account-product/invalid-interest-terms`,
   with the currency's day count and business day convention.
2. **`term-months`** on the version, 1 to 120, with the payout account
   named when an account opens, as the TDD's Term section says. The
   migration refusal `:cash-account-migration/fixed-term-version` comes
   with it.
3. **The console sends `banding: marginal`** every time.

## Open decisions

- **Creating a product.** Keep the drawer for type, currency and name,
  then land on the page? Or open the page empty?
- **Whole-balance banding.** The grid shows any schedule the API
  takes, except whole-balance banding, which the page does not offer.
  Refuse it in the domain, or show it read-only?
- **A maturity date.** "Until a fixed date" with "The account matures"
  has no field. Offer a fixed date only with "The rate changes", or add
  a maturity date to the record.
- **Withdrawals to the customer's own accounts.** A term of the version
  checked by the payment brick, or a policy the version binds?
  [product-terms](product-terms.md) holds the question.
- **A rate change followed by maturity** (a bonus for 6 months inside a
  12-month term) is left out on purpose. Say so if a product needs it.

## Out of scope

Eligibility (one account per customer, only for holders of another
product), rates that depend on what the customer does, and day count as
a choice.

## Tests

- **Console e2e** beside `e2e/scenarios.spec.mjs`: create both example
  products through the page and read them back as stated; a refused
  save shows a `danger` `Callout` with the status and type, such as
  `422 · :cash-account-product/invalid-limit`.
- **`cash-account-product`**: interest terms kept through draft, update
  and publish; each validation rule refused with its message.
- **`test-api-scenarios`**: the two request bodies above, returned as
  sent.
