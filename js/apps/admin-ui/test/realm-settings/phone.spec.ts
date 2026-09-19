import { test } from "@playwright/test";
import { v4 as uuid } from "uuid";
import adminClient from "../utils/AdminClient.ts";
import { login } from "../utils/login.ts";
import { goToRealm, goToRealmSettings } from "../utils/sidebar.ts";
import { assertEmptyTable } from "../utils/table.ts";
import {
  assertAddSenderHidden,
  assertNoSenderProviders,
  goToPhone,
} from "./phone.ts";

test.describe.serial("Realm Settings - Phone", () => {
  const realmName = `phone-realm-settings-${uuid()}`;

  test.beforeAll(() => adminClient.createRealm(realmName));
  test.afterAll(() => adminClient.deleteRealm(realmName));

  test.beforeEach(async ({ page }) => {
    await login(page);
    await goToRealm(page, realmName);
    await goToRealmSettings(page);
    await goToPhone(page);
  });

  // Keycloak ships no phone message sender, so on a stock server there is nothing to add.
  // The tab has to say so rather than offer a button that leads nowhere.
  test("Says when no sender providers are deployed", async ({ page }) => {
    await assertEmptyTable(page);
    await assertNoSenderProviders(page);
    await assertAddSenderHidden(page);
  });
});
