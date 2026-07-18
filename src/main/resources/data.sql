-- Lookup/reference seed data only — templates, fields and validation rules are
-- created by users through the UI. Loaded on the H2 profiles (local/test);
-- for SQL Server run it once manually.

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

-- Demo login for local/dev profiles (email: demo@modocument.local, password: Demo123!).
INSERT INTO AppUsers (Email, PasswordHash, DisplayName, SettingsJson, CreatedDate, UpdatedDate) VALUES ('demo@modocument.local', '$2a$10$B2c04DCSNn/ji4VIRRUdTOJukpR6E1cqynSre.sJiPPaFGTY2GNuy', 'Demo User', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
ALTER TABLE AppUsers ALTER COLUMN AppUserId RESTART WITH 100;
