import { type Page, expect } from "@playwright/test";

export async function goToPhone(page: Page) {
  await page.getByTestId("rs-phone-tab").click();
}

export async function assertNoSenderProviders(page: Page) {
  await expect(page.getByTestId("empty-state")).toContainText(
    "No phone message sender providers are deployed on this server.",
  );
}

export async function assertAddSenderHidden(page: Page) {
  await expect(page.getByTestId("add-phone-sender")).toBeHidden();
  await expect(
    page.getByTestId("no-senders-configured-empty-action"),
  ).toBeHidden();
}
