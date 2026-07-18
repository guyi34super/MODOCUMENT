-- Lookup/reference seed data for the H2 test profile only — every other
-- profile runs PostgreSQL, seeded by Flyway (src/main/resources/db/migration);
-- templates, fields, validation rules and users are created through the app.

-- TemplateDataTypes
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (1, 'Text');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (2, 'Number');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (3, 'Date');

-- ProcessStep (the "Tansforming" typo is preserved from the original seed)
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (1, 'Tansforming', 'ProcessStep1');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (2, 'Mapping', 'ProcessStep2');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (3, 'Validating', 'ProcessStep3');

-- Move the identity sequences past the seeded ids so runtime inserts do not collide (H2 syntax).
ALTER TABLE TemplateDataTypes ALTER COLUMN TemplateDataTypeId RESTART WITH 100;
ALTER TABLE ProcessStep ALTER COLUMN ProcessStepId RESTART WITH 100;
