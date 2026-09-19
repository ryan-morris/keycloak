import {
  DataList,
  DataListCell,
  DataListItem,
  DataListItemCells,
  DataListItemRow,
  Modal,
} from "@patternfly/react-core";
import { useTranslation } from "react-i18next";
import { useServerInfo } from "../../context/server-info/ServerInfoProvider";
import { PHONE_SENDER_TYPE } from "../../util";

type PhoneSenderPickerProps = {
  onConfirm: (provider: string) => void;
  onClose: () => void;
};

export const PhoneSenderPicker = ({
  onConfirm,
  onClose,
}: PhoneSenderPickerProps) => {
  const { t } = useTranslation();
  const serverInfo = useServerInfo();
  const providerTypes = serverInfo.componentTypes?.[PHONE_SENDER_TYPE] ?? [];
  return (
    <Modal
      variant="medium"
      title={t("addPhoneSender")}
      isOpen
      onClose={onClose}
    >
      <DataList
        onSelectDataListItem={(_event, id) => {
          onConfirm(id);
        }}
        aria-label={t("addPhoneSender")}
        isCompact
      >
        {providerTypes.map((provider) => (
          <DataListItem
            aria-label={provider.id}
            key={provider.id}
            id={provider.id}
          >
            <DataListItemRow>
              <DataListItemCells
                dataListCells={[
                  <DataListCell
                    key={`name-${provider.id}`}
                    data-testid={`option-${provider.id}`}
                  >
                    {provider.id}
                  </DataListCell>,
                  <DataListCell width={2} key={`helpText-${provider.id}`}>
                    {provider.helpText}
                  </DataListCell>,
                ]}
              />
            </DataListItemRow>
          </DataListItem>
        ))}
      </DataList>
    </Modal>
  );
};
