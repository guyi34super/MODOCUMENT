// What the Validation page (Document Creation step 2) calls the data field kinds and PII classes.

/** The data field kinds (DocDataField.Kind) as the Fields card names them. */
export const DATA_KIND_LABELS = {
  TEXT: 'Text',
  MULTILINE: 'Long text',
  DATE: 'Date',
  NUMBER: 'Number',
  CHOICE: 'Dropdown',
  CHECKBOX: 'Checkbox',
  IMAGE: 'Photo',
};

/** PII classes of a data field (the flow's PiiKind); '' = not set. */
export const PII_KINDS = [
  { value: '', label: 'Not set' },
  { value: 'NAME', label: 'Name' },
  { value: 'NIC', label: 'NIC' },
  { value: 'ACCOUNT_NUMBER', label: 'Account number' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone' },
  { value: 'NONE', label: 'Not PII' },
];
