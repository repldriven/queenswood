import { test, expect } from "@playwright/test";

// Signs in through Keycloak's own form as a member of a bank with
// nothing in it yet, opens the Scenarios page in autoplay, and waits
// for the eleven scenes to run through, each followed by a few seconds
// on the view where it pays off. The video the fixture records of it
// is the deliverable as much as the assertion.
const user = process.env.CONSOLE_USER ?? "xepha";
const password = process.env.CONSOLE_PASSWORD ?? "xepha";

test("the eleven scenes run through in order", async ({ page }) => {
  await page.goto("/#/sign-in");
  // The third option is Keycloak's own username and password form.
  await page
    .locator("button.opt")
    .filter({ hasNotText: /google|github/i })
    .first()
    .click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await page.waitForURL(/#\//);
  await expect(page.getByText("Scenarios")).toBeVisible({ timeout: 60_000 });

  // A hash change rather than a navigation, so the signed-in app stays
  // as it is and the page mounts with the flag.
  await page.evaluate(() => {
    location.hash = "#/scenarios?autoplay";
  });
  await page.waitForSelector('[data-autoplay="done"], [data-autoplay="failed"]', {
    state: "attached",
    timeout: 18 * 60 * 1000,
  });
  await expect(page.locator("[data-autoplay]")).toHaveAttribute("data-autoplay", "done");
  await page.waitForTimeout(3_000);
});
