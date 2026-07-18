-- Lookup/reference data only — templates, fields, validation rules and users
-- are all created through the application.

INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (1, 'Text');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (2, 'Number');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (3, 'Date');

-- (the "Tansforming" typo is preserved from the original seed)
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (1, 'Tansforming', 'ProcessStep1');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (2, 'Mapping', 'ProcessStep2');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (3, 'Validating', 'ProcessStep3');

-- Move the identity sequences past the seeded ids so runtime inserts do not collide.
ALTER TABLE TemplateDataTypes ALTER COLUMN TemplateDataTypeId RESTART WITH 100;
ALTER TABLE ProcessStep ALTER COLUMN ProcessStepId RESTART WITH 100;
