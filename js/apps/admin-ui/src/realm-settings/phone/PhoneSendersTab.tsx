import type ComponentRepresentation from "@keycloak/keycloak-admin-client/lib/defs/componentRepresentation";
import type ComponentTypeRepresentation from "@keycloak/keycloak-admin-client/lib/defs/componentTypeRepresentation";
import {
  KeycloakDataTable,
  ListEmptyState,
  useAlerts,
} from "@keycloak/keycloak-ui-shared";
import {
  AlertVariant,
  Button,
  ButtonVariant,
  PageSection,
  ToolbarItem,
} from "@patternfly/react-core";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../../admin-client";
import { useConfirmDialog } from "../../components/confirm-dialog/ConfirmDialog";
import { useAccess } from "../../context/access/Access";
import { useRealm } from "../../context/realm-context/RealmContext";
import { useServerInfo } from "../../context/server-info/ServerInfoProvider";
import { PHONE_SENDER_TYPE } from "../../util";
import useToggle from "../../utils/useToggle";
import { PhoneSenderModal } from "./PhoneSenderModal";
import { PhoneSenderPicker } from "./PhoneSenderPicker";

/**
 * The senders a realm can use to reach a phone number.
 *
 * Keycloak ships none: reaching a phone means going through a particular service, so what
 * appears in the "Add sender" picker is whatever providers are deployed.
 */
export const PhoneSendersTab = () => {
  const { adminClient } = useAdminClient();
  const { t } = useTranslation();
  const { realmRepresentation } = useRealm();
  const { addAlert, addError } = useAlerts();

  const { hasAccess } = useAccess();
  // read-only administrators should not be offered controls the server will refuse
  const canManage = hasAccess("manage-realm");

  const serverInfo = useServerInfo();
  const providerTypes: ComponentTypeRepresentation[] =
    serverInfo.componentTypes?.[PHONE_SENDER_TYPE] ?? [];

  const [key, setKey] = useState(0);
  const refresh = () => setKey(key + 1);

  const [pickerOpen, togglePicker] = useToggle();
  const [editing, setEditing] = useState<ComponentRepresentation>();
  const [creatingWith, setCreatingWith] =
    useState<ComponentTypeRepresentation>();
  const [selected, setSelected] = useState<ComponentRepresentation>();

  const loader = () =>
    adminClient.components.find({
      parent: realmRepresentation.id,
      type: PHONE_SENDER_TYPE,
    });

  const [toggleDeleteDialog, DeleteConfirm] = useConfirmDialog({
    titleKey: "deletePhoneSenderTitle",
    messageKey: t("deletePhoneSenderConfirm", { name: selected?.name }),
    continueButtonLabel: "delete",
    continueButtonVariant: ButtonVariant.danger,
    onConfirm: async () => {
      try {
        await adminClient.components.del({ id: selected!.id! });
        addAlert(t("deletePhoneSenderSuccess"), AlertVariant.success);
        refresh();
      } catch (error) {
        addError("deletePhoneSenderError", error);
      }
    },
  });

  const providerOf = (sender: ComponentRepresentation) =>
    providerTypes.find((type) => type.id === sender.providerId);

  return (
    <PageSection variant="light">
      <DeleteConfirm />
      {pickerOpen && (
        <PhoneSenderPicker
          onClose={togglePicker}
          onConfirm={(provider) => {
            setCreatingWith(providerTypes.find((type) => type.id === provider));
            togglePicker();
          }}
        />
      )}
      {(editing || creatingWith) && (
        <PhoneSenderModal
          providerType={
            (editing
              ? providerOf(editing)
              : creatingWith) as ComponentTypeRepresentation
          }
          sender={editing}
          onClose={() => {
            setEditing(undefined);
            setCreatingWith(undefined);
          }}
          onSaved={() => {
            setEditing(undefined);
            setCreatingWith(undefined);
            refresh();
          }}
        />
      )}
      <KeycloakDataTable
        key={key}
        loader={loader}
        ariaLabelKey="phoneSenders"
        toolbarItem={
          <ToolbarItem>
            <Button
              data-testid="add-phone-sender"
              isDisabled={providerTypes.length === 0 || !canManage}
              onClick={togglePicker}
            >
              {t("addPhoneSender")}
            </Button>
          </ToolbarItem>
        }
        actions={
          canManage
            ? [
                {
                  title: t("delete"),
                  onRowClick: (sender) => {
                    setSelected(sender);
                    toggleDeleteDialog();
                  },
                },
              ]
            : []
        }
        columns={[
          {
            name: "name",
            displayKey: "name",
            cellRenderer: (sender) => (
              <Button
                variant="link"
                isInline
                // a sender whose provider is no longer deployed has no form to show,
                // but it must stay listed so that it can be removed
                isDisabled={!providerOf(sender)}
                onClick={() => setEditing(sender)}
              >
                {sender.name}
              </Button>
            ),
          },
          {
            name: "providerId",
            displayKey: "provider",
          },
          {
            name: "providerId",
            displayKey: "description",
            cellRenderer: (sender) =>
              providerOf(sender)?.helpText ?? t("phoneSenderNotDeployed"),
          },
        ]}
        emptyState={
          <ListEmptyState
            message={t("noPhoneSenders")}
            instructions={
              providerTypes.length === 0
                ? t("noPhoneSenderProvidersInstructions")
                : t("noPhoneSendersInstructions")
            }
            primaryActionText={
              providerTypes.length === 0 || !canManage
                ? undefined
                : t("addPhoneSender")
            }
            onPrimaryAction={togglePicker}
          />
        }
      />
    </PageSection>
  );
};
