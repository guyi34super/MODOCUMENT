-- Seed data ported verbatim from the original EF Core DbInitializer (HasData).
-- Loaded on the H2 profiles (local/test); for SQL Server run it once manually.

-- TemplateDataTypes
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (1, 'Text');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (2, 'Number');
INSERT INTO TemplateDataTypes (TemplateDataTypeId, DataType) VALUES (3, 'Date');

-- Template
INSERT INTO Template (TemplateId, Name, Description) VALUES (1, 'Employee Profile', 'Employee Profile');
INSERT INTO Template (TemplateId, Name, Description) VALUES (2, 'Employee Timesheet', 'Employee Timesheet');
INSERT INTO Template (TemplateId, Name, Description) VALUES (3, 'Employee Direct Deposit', 'Employee Direct Deposit');
INSERT INTO Template (TemplateId, Name, Description) VALUES (4, 'Employee Deductions/Contributions', 'Employee Deductions/Contributions');

-- TemplateFields (Employee Profile, template 1)
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (1, 1, 'DayforceBranchNo', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (2, 1, 'DayforcePayrollNumber', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (3, 1, 'EmployeeNumber', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (4, 1, 'FirstName', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (5, 1, 'LastName', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (6, 1, 'MiddleInitial', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (7, 1, 'SIN', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (8, 1, 'SinExpiryDate', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (9, 1, 'Street', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (10, 1, 'City', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (11, 1, 'Province/State', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (12, 1, 'Country', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (13, 1, 'Postal/ZipCode', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (14, 1, 'PhoneNumber', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (15, 1, 'Phone2', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (16, 1, 'Phone3', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (17, 1, 'Email', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (18, 1, 'AdditionalEmail', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (19, 1, 'PreferredLanguage', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (20, 1, 'Gender', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (21, 1, 'BirthDate', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (22, 1, 'FirstDayWorked', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (23, 1, 'ROEFirstDayWorked', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (24, 1, 'PayType', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (25, 1, 'PayRate', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (26, 1, 'StandardHoursPerPay', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (27, 1, 'EmploymentType', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (28, 1, 'ProvinceOfEmployment', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (29, 1, 'FederalTaxExemption', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (30, 1, 'ProvincialTaxExemption', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (31, 1, 'TaxStatus', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (32, 1, 'CPP/QPPStatus', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (33, 1, 'EICategory', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (34, 1, 'ProvincialPayroll/HealthTaxIndicator', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (35, 1, 'ProvincialSafetyPlans', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (36, 1, 'QPIP', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (37, 1, 'QuebecHealthContribution', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (38, 1, 'QuebecConstruction', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (39, 1, 'PositionReferenceCode', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (40, 1, 'Department', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (41, 1, 'PositionTitle', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (42, 1, 'IDY', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (43, 1, 'IDZ', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (44, 1, 'IDX', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (45, 1, 'VacationPlan', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (46, 1, 'VacationRate', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (47, 1, 'AdditionalAccumulatorPlan', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (48, 1, 'AdditionalAccumulatorRate', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (49, 1, 'SickPayAccumulatorPlan', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (50, 1, 'SickPayAccumulatorHoursPerDay', 2);

-- TemplateFields (Employee Timesheet, template 2) — id 60 intentionally absent, as in the original seed
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (51, 2, 'DayforceBranchNo', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (52, 2, 'DayforcePayrollNumber', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (53, 2, 'EmployeeNumber', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (54, 2, 'ApplicablePeriodOfTime', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (55, 2, 'EarningCode', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (56, 2, 'Hours', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (57, 2, 'ExceptionRate', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (58, 2, 'Amount', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (59, 2, 'PositionReferenceCode', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (61, 2, 'DistributionIdDept', 2);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (62, 2, 'DistributionIDY', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (63, 2, 'DistributionIDZ', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (64, 2, 'DistributionIDX', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (65, 2, 'ForWhichPayPeriod', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (66, 2, 'VacationPayAccumulatorType', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (67, 2, 'VAPayoutForPayPeriodEnding', 3);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (68, 2, 'AdditionalAccumulatorPayoutType', 1);
INSERT INTO TemplateFields (TemplateFieldsId, TemplateId, FieldName, TemplateDataTypeId) VALUES (69, 2, 'WAPayoutForPayPeriodEnding', 3);

-- ProcessStep (the "Tansforming" typo is preserved from the original seed)
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (1, 'Tansforming', 'ProcessStep1');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (2, 'Mapping', 'ProcessStep2');
INSERT INTO ProcessStep (ProcessStepId, Name, Description) VALUES (3, 'Validating', 'ProcessStep3');

-- ValidationRules. RuleType ordinals: ConditionalRequirement=0, DateFormat=1,
-- ExpiryDate=2, Required=3, Regex=4, Range=5, Type=6, Custom=7.
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'DayforceBranchNo', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'DayforceBranchNo', 4, '{"Pattern":"^(PP(1|2|3|4|7|9))$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'DayforcePayrollNumber', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'DayforcePayrollNumber', 4, '{"Pattern": "^.{4}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'EmployeeNumber', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'EmployeeNumber', 4, '{"Pattern": "^(?!000000000$)(?!0{3,5}[0-9]{4,6}$)(?!9999[0-9]{5}$)\\d{1,9}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'FirstName', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'FirstName', 4, '{"Pattern": "^[a-zA-Z0-9_ -.‘&\/\\()@#]{1,25}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'LastName', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'LastName', 4, '{"Pattern": "^[a-zA-Z0-9_ -.‘&\/\\()@#]{1,25}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'MiddleInitial', 4, '{"Pattern": "^[a-zA-Z0-9_ -.‘&\/\\()@#]{1,4}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'SIN', 4, '{"Pattern": "^(?![08])\\d{3} \\d{3} \\d{3}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'SINExpiryDate', 1, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'SINExpiryDate', 2, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Street', 4, '{"Pattern": "^[a-zA-Z0-9_ -.‘,:&\/\\()#]{1,34}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'City', 4, '{"Pattern": "^[a-zA-Z0-9_ -.‘,:&\/\\()#]{1,18}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Province/State', 4, '{"Pattern":"^(AB|BC|MB|NB|NL|NN|NS|NT|ON|PE|QC|SK|YT|US|OT|AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Country', 4, '{"Pattern":"^(CAN|USA)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Postal/ZipCode', 4, '{"Pattern":"^([ABCEGHJ-NPRSTVXY]\\d[ABCEGHJ-NPRSTV-Z][ ]?\\d[ABCEGHJ-NPRSTV-Z]\\d|\\d{5}?)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PhoneNumber', 4, '{"Pattern":"^(\\d{10}|\\d{3}-\\d{3}-\\d{4})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Phone2', 4, '{"Pattern":"^(\\d{10}|\\d{3}-\\d{3}-\\d{4})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Phone3', 4, '{"Pattern":"^(\\d{10}|\\d{3}-\\d{3}-\\d{4})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Email', 4, '{"Pattern": "^[a-zA-Z0-9_\\-.@]{1,80}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'AdditionalEmail', 4, '{"Pattern": "^[a-zA-Z0-9_\\-.@]{1,80}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PreferredLanguage', 4, '{"Pattern":"^(E|F)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Gender', 4, '{"Pattern":"^(M|F|U)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'BirthDate', 1, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'FirstDayWorked', 1, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ROEFirstDayWorked', 1, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PayType', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PayType', 4, '{"Pattern":"^(S|L|H|V|W|A|C|K|1|2)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PayRate', 4, '{"Pattern":"^(\\d{1,6}.\\d{2})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'StandardHoursPerPay', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'StandardHoursPerPay', 4, '{"Pattern":"^(\\d{1,3}.\\d{2})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'EmploymentType', 4, '{"Pattern":"^(F|P)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ProvinceOfEmployment', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ProvinceOfEmployment', 4, '{"Pattern":"^(AB|BC|MB|NB|NL|NN|NS|NT|ON|PE|QC|SK|YT|US|OT|AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'FederalTaxExemption', 4, '{"Pattern":"^(\\d{1,5})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ProvincialTaxExemption', 4, '{"Pattern":"^(\\d{1,5})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'TaxStatus', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'TaxStatus', 4, '{"Pattern":"^(1|X|F|M|A|I|P|N|V)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'CPP/QPPStatus', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'CPP/QPPStatus', 4, '{"Pattern":"^(X|0)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'EICategory', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'EICategory', 4, '{"Pattern":"^(P|0|2|N|X|B|E|J)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ProvincialPayroll/HealthTaxIndicator', 4, '{"Pattern":"^[a-zA-Z0-9]{1}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'ProvincialSafetyPlans', 4, '{"Pattern":"^[a-zA-Z0-9]{1}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'QPIP', 4, '{"Pattern":"^(0|1)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'QuebecHealthContribution', 4, '{"Pattern":"^(0|N)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'QuebecConstruction', 4, '{"Pattern":"^(0|Y)$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PositionReferenceCode', 4, '{"Pattern": "^[a-zA-Z0-9&-\/\\.'',:()_#;]{1,10}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Department', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'Department', 4, '{"Pattern":"^[a-zA-Z0-9]{3}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'PositionTitle', 4, '{"Pattern": "^[a-zA-Z0-9&-\/\\.'',:()_#;]{1,30}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'IDY', 4, '{"Pattern": "^[a-zA-Z0-9''.-\/\\#@&*+]{1,2}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'IDZ', 4, '{"Pattern": "^[a-zA-Z0-9''.-\/\\#@&*+]{1,2}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'IDX', 4, '{"Pattern": "^[a-zA-Z0-9''.-\/\\#@&*+]{1,2}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'VacationPlan', 3, '{}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'VacationPlan', 4, '{"Pattern":"^[a-zA-Z0-9]{1}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'VacationRate', 4, '{"Pattern":"^(\\d{1,2}.\\d{2})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'AdditionalAccumulatorPlan', 4, '{"Pattern":"^[a-zA-Z0-9]{1}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'AdditionalAccumulatorRate', 4, '{"Pattern":"^(\\d{1,2}.\\d{2})$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'SickPayAccumulatorPlan', 4, '{"Pattern":"^[a-zA-Z0-9]{1}$"}');
INSERT INTO ValidationRules (TemplateId, FieldName, RuleType, ConfigJson) VALUES (1, 'SickPayAccumulatorHoursPerPay', 4, '{"Pattern":"^(\\d{1,2}.\\d{2})$"}');

-- Move the identity sequences past the seeded ids so runtime inserts do not collide (H2 syntax).
ALTER TABLE TemplateDataTypes ALTER COLUMN TemplateDataTypeId RESTART WITH 100;
ALTER TABLE Template ALTER COLUMN TemplateId RESTART WITH 100;
ALTER TABLE TemplateFields ALTER COLUMN TemplateFieldsId RESTART WITH 100;
ALTER TABLE ProcessStep ALTER COLUMN ProcessStepId RESTART WITH 100;

-- Demo login for local/dev profiles (email: demo@modocument.local, password: Demo123!).
INSERT INTO AppUsers (Email, PasswordHash, DisplayName, SettingsJson, CreatedDate, UpdatedDate) VALUES ('demo@modocument.local', '$2a$10$B2c04DCSNn/ji4VIRRUdTOJukpR6E1cqynSre.sJiPPaFGTY2GNuy', 'Demo User', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
ALTER TABLE AppUsers ALTER COLUMN AppUserId RESTART WITH 100;
