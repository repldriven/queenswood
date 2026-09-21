import { test, expect } from "@playwright/test";

// A new customer's walk through the app, filmed: sign up and be
// verified, open an Everyday and be paid its welcome reward, then open
// a Rainy Day with some of it. It runs against the bank the console's
// scenes have been through. The reward is paid by the platform's hourly
// job, which the recipe forces once the Everyday exists, so the walk
// waits on the balance rather than on the job. Every screen is held a
// beat for the viewer, and typed fields are typed.
const bank = process.env.DEMO_BANK_URL ?? "http://localhost:8100";
// A number no earlier customer signed up with, since sign-in resolves
// a customer by phone.
const phone = "7700 9" + String(Date.now()).slice(-5);
const passcode = "2468";
const BEAT = 1800;

const screen = (page, label) => page.locator(`[data-screen-label="${label}"]`);
const button = (page, name) => page.getByRole("button", { name, exact: true });
const type = (locator, text) => locator.pressSequentially(text, { delay: 70 });
async function tap(page, digits) {
  for (const d of digits) {
    await page.locator(".pad").getByRole("button", { name: d, exact: true }).click();
    await page.waitForTimeout(160);
  }
}
async function openAccount(page, name, deposit) {
  await page.locator(".qa").getByRole("button", { name: "Open", exact: true }).click();
  await expect(screen(page, "Choose a product")).toBeVisible();
  await page.waitForTimeout(BEAT);
  await page.locator("button.opt", { hasText: name }).click();
  await page.waitForTimeout(600);
  await button(page, "Continue").click();
  await expect(screen(page, "Confirm new account")).toBeVisible();
  await page.waitForTimeout(BEAT);
  if (deposit) await type(page.locator('input.inp.num[placeholder="£0.00"]'), deposit);
  await page.waitForTimeout(600);
  await button(page, `Open ${name}`).click();
  await expect(screen(page, "Account opened")).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(BEAT);
  await button(page, "Done").click();
  await expect(screen(page, "Home")).toBeVisible();
}

test("a new customer signs up and opens two accounts", async ({ page }) => {
  await page.goto("/");
  await expect(screen(page, "Welcome")).toBeVisible();
  await page.waitForTimeout(BEAT);
  await button(page, "Get started").click();

  // Sign up: the number, the code the app fills in itself, the details,
  // the document scan, and a passcode chosen twice.
  await expect(screen(page, "Mobile number")).toBeVisible();
  await type(page.locator('input.inp[placeholder="7700 900123"]'), phone);
  await page.waitForTimeout(600);
  await button(page, "Send code").click();
  await expect(screen(page, "About you")).toBeVisible({ timeout: 30_000 });
  await type(page.locator('input.inp[placeholder="Amara"]'), "Hotblack");
  await type(page.locator('input.inp[placeholder="Okafor"]'), "Desiato");
  await type(page.locator('input.inp[placeholder="DD / MM / YYYY"]'), "31101979");
  await type(page.locator('input.inp[placeholder="12"]'), "1");
  await type(page.locator('input.inp[placeholder="Mare Street"]'), "Disaster Area");
  await type(page.locator('input.inp[placeholder="London"]'), "London");
  await type(page.locator('input.inp[placeholder="E8 3RH"]'), "E8 3RH");
  await type(page.locator('input.inp[placeholder="QQ123456C"]'), "QQ123456C");
  await page.waitForTimeout(600);
  await button(page, "Continue").click();
  await expect(screen(page, "ID check")).toBeVisible();
  await page.waitForTimeout(BEAT);
  await button(page, "Scan document").click();
  await button(page, "Continue").click({ timeout: 60_000 });
  await expect(screen(page, "Passcode")).toBeVisible();
  await page.waitForTimeout(600);
  await tap(page, passcode);
  await page.waitForTimeout(600);
  await tap(page, passcode);
  await expect(screen(page, "Signed up")).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(BEAT);
  await button(page, "Go to my account").click();
  await expect(screen(page, "Home")).toBeVisible({ timeout: 30_000 });

  // The check runs in the background, and an account cannot open until
  // it clears. Read the bank's own answer rather than guess at the time.
  const token = await page.evaluate(() => localStorage.getItem("xepha.session"));
  await expect
    .poll(
      async () => {
        const r = await page.request.get(`${bank}/me`, {
          headers: { authorization: `Bearer ${token}` },
        });
        return (await r.json()).user?.verification;
      },
      { timeout: 90_000, intervals: [1000] },
    )
    .toBe("verified");
  await page.waitForTimeout(BEAT);

  // An Everyday, and the £50 the product promised, paid on the hour.
  await openAccount(page, "Everyday");
  await expect(page.locator(".big.num")).toHaveText("£50.00", { timeout: 4 * 60 * 1000 });
  await page.waitForTimeout(BEAT * 2);

  // A Rainy Day, with £20 of it.
  await openAccount(page, "Rainy Day", "20");
  await expect(page.locator(".acct.sav")).toBeVisible();
  await page.waitForTimeout(BEAT * 2);
});
