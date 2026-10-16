import { test, expect } from "@playwright/test";

// The product version page, against a console and a platform already
// up, signed in as a member of a bank whose tier allows a savings and a
// term-deposit product. It creates the plan's two products through the
// page, reads them back as stated, and shows a refused save as the API
// answered it.
const user = process.env.CONSOLE_USER ?? "xepha";
const password = process.env.CONSOLE_PASSWORD ?? "xepha";
const stamp = Date.now().toString(36);

async function signIn(page) {
  await page.goto("/#/sign-in");
  await page
    .locator("button.opt")
    .filter({ hasNotText: /google|github/i })
    .first()
    .click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL(/#\//);
  await expect(page.getByRole("heading", { name: "Products" })).toBeVisible({
    timeout: 60_000,
  });
}

async function createDraft(page, type, name) {
  await page.getByRole("button", { name: "New product" }).click();
  await page.locator("#f-type").selectOption(type);
  await page.locator("#f-name").fill(name);
  await page.getByRole("button", { name: "Create draft" }).click();
  await page.waitForURL(/#\/products\/prd\.[^/]+\/versions\/prv\./);
  await expect(page.getByRole("heading", { name })).toBeVisible();
}

async function setCell(page, label, value) {
  const input = page.getByLabel(label, { exact: true });
  await input.fill(value);
  await input.blur();
}

async function save(page) {
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("button", { name: "Save draft" })).toBeDisabled();
}

const terms = (page) => page.locator("dl.terms");

test("a banded saver with a reversion rate is stated and read back", async ({ page }) => {
  await signIn(page);
  const name = `Easy Saver ${stamp}`;
  await createDraft(page, "savings", name);

  await page.getByRole("radio", { name: "Accrues interest" }).click();
  await page.getByRole("button", { name: "Add band" }).click();
  await setCell(page, "Band 2 starts at", "5000");
  await setCell(page, "Rate in period 1, band 1", "5.00");
  await setCell(page, "Rate in period 1, band 2", "0");
  await page.getByRole("button", { name: "Add period" }).click();
  await setCell(page, "Period 2 starts after", "12");
  await setCell(page, "Rate in period 2, band 1", "1.50");
  await setCell(page, "Rate in period 2, band 2", "1.50");

  await page.getByRole("button", { name: "Add payment limit" }).click();
  await page.getByLabel("Direction").selectOption("out");
  await page.getByLabel("Measure").selectOption("count");
  await setCell(page, "At most", "3");
  await page.getByLabel("Each").selectOption("year");
  await page.getByLabel("Counted from").selectOption("calendar");
  await page.getByLabel("Day").selectOption("6");
  await page.getByLabel("Month").selectOption("4");
  await setCell(page, "Reason", "Three withdrawals a tax year");

  await page.getByRole("radio", { name: "Welcome reward" }).click();
  await setCell(page, "Amount", "25");
  await save(page);

  await page.reload();
  await expect(terms(page)).toContainText(
    "5.00% on the first £5,000.00, 0.00% above £5,000.00 from opening; then 1.50% on the whole balance from 12 months after opening",
  );
  await expect(terms(page)).toContainText(
    "At most 3 payments out each year, counted from 6 April.",
  );
  await expect(terms(page)).toContainText("A welcome reward of £25.00");
  await expect(page.getByText("Actual/365 · modified following")).toBeVisible();

  await page.getByRole("button", { name: /^Publish v1$/ }).click();
  await expect(page.locator(".aside")).toContainText("published");
});

test("a flat regular saver paid monthly states its AER", async ({ page }) => {
  await signIn(page);
  const name = `Regular Saver ${stamp}`;
  await createDraft(page, "term-deposit", name);

  await page.getByRole("radio", { name: "Accrues interest" }).click();
  await setCell(page, "Rate in period 1, band 1", "6.31");
  await page.getByRole("button", { name: "Add early window" }).click();
  await setCell(page, "Window 1 length", "12");
  await page.getByLabel("Window 1 kind").selectOption("charged");
  await page.getByLabel("Window 1 interest", { exact: true }).selectOption("forfeit-days");
  await setCell(page, "Window 1 interest days", "90");
  await page.getByRole("button", { name: "Add payment limit" }).click();
  await setCell(page, "At most", "300");
  await save(page);

  await page.reload();
  await expect(terms(page)).toContainText("6.31% a year gross (6.50% AER) on the whole balance");
  await expect(terms(page)).toContainText(
    "Closed in its first 12 months, it loses 90 days' interest, or what has not yet been paid if that is less.",
  );
  await expect(terms(page)).toContainText(
    "Up to £300.00 paid in each month, counted from the opening day.",
  );
});

test("a refused save shows the problem the API answered", async ({ page }) => {
  await signIn(page);
  const name = `Refused ${stamp}`;
  await createDraft(page, "current", name);

  await page.getByRole("radio", { name: "Accrues interest" }).click();
  await page.getByRole("button", { name: "Add band" }).click();
  await page.getByRole("button", { name: "Add band" }).click();
  await setCell(page, "Band 2 starts at", "5000");
  await setCell(page, "Band 3 starts at", "4000");
  await page.getByRole("button", { name: "Save draft" }).click();

  const refusal = page.getByRole("alert").filter({ hasText: "The draft was not saved" });
  await expect(refusal).toContainText("Bands start at zero, each above the one before");
  await expect(refusal).toContainText("422 · :cash-account-product/invalid-interest-terms");
});
