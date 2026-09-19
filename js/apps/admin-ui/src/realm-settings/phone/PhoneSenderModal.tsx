import type ComponentRepresentation from "@keycloak/keycloak-admin-client/lib/defs/componentRepresentation";
import type ComponentTypeRepresentation from "@keycloak/keycloak-admin-client/lib/defs/componentTypeRepresentation";
import { TextControl, useAlerts } from "@keycloak/keycloak-ui-shared";
import {
  AlertVariant,
  Button,
  ButtonVariant,
  Modal,
  ModalVariant,
} from "@patternfly/react-core";
import { FormProvider, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../../admin-client";
import { DynamicComponents } from "../../components/dynamic/DynamicComponents";
import { FormAccess } from "../../components/form/FormAccess";
import { useAccess } from "../../context/access/Access";
import { PHONE_SENDER_TYPE } from "../../util";

type PhoneSenderModalProps = {
  providerType: ComponentTypeRepresentation;
  sender?: ComponentRepresentation;
  onClose: () => void;
  onSaved: () => void;
};

export const PhoneSenderModal = ({
  providerType,
  sender,
  onClose,
  onSaved,
}: PhoneSenderModalProps) => {
  const { adminClient } = useAdminClient();
  const { t } = useTranslation();
  const { addAlert, addError } = useAlerts();
  const { hasAccess } = useAccess();

  const form = useForm<ComponentRepresentation>({
    mode: "onChange",
    defaultValues: sender ?? { name: providerType.id },
  });
  const { handleSubmit } = form;

  const save = async (component: ComponentRepresentation) => {
    // the dynamic form gives single values, the component model stores lists
    if (component.config) {
      Object.entries(component.config).forEach(
        ([key, value]) =>
          (component.config![key] = Array.isArray(value) ? value : [value]),
      );
    }

    try {
      if (sender?.id) {
        await adminClient.components.update(
          { id: sender.id },
          { ...component, providerType: PHONE_SENDER_TYPE },
        );
      } else {
        await adminClient.components.create({
          ...component,
          providerId: providerType.id,
          providerType: PHONE_SENDER_TYPE,
        });
      }
      addAlert(t("phoneSenderSaveSuccess"), AlertVariant.success);
      onSaved();
    } catch (error) {
      addError("phoneSenderSaveError", error);
    }
  };

  return (
    <Modal
      title={sender ? t("editPhoneSender") : t("addPhoneSender")}
      isOpen
      variant={ModalVariant.medium}
      onClose={onClose}
      actions={[
        <Button
          key="confirm"
          data-testid="phone-sender-save"
          isDisabled={!hasAccess("manage-realm")}
          onClick={handleSubmit(save)}
        >
          {t("save")}
        </Button>,
        <Button
          key="cancel"
          variant={ButtonVariant.link}
          data-testid="phone-sender-cancel"
          onClick={onClose}
        >
          {t("cancel")}
        </Button>,
      ]}
    >
      <FormAccess
        id="phone-sender-form"
        isHorizontal
        role="manage-realm"
        onSubmit={handleSubmit(save)}
      >
        <FormProvider {...form}>
          <TextControl
            name="name"
            label={t("name")}
            labelIcon={t("phoneSenderNameHelp")}
            rules={{ required: t("required") }}
          />
          <DynamicComponents properties={providerType.properties} />
        </FormProvider>
      </FormAccess>
    </Modal>
  );
};
