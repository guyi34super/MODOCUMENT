using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Infrastructure.Persistence
{
	public class DbInitializer
	{
		private readonly ModelBuilder modelBuilder;
		public DbInitializer(ModelBuilder modelBuilder)
		{
			this.modelBuilder = modelBuilder;
		}

		public void Seed()
		{
            #region TemplateType
            modelBuilder.Entity<TemplateDataType>().HasData(
				new TemplateDataType { TemplateDataTypeId = 1, DataType = "Text"   },
				new TemplateDataType { TemplateDataTypeId = 2, DataType = "Number" },
				new TemplateDataType { TemplateDataTypeId = 3, DataType = "Date"   }
			);
            #endregion

            #region Template
            modelBuilder.Entity<Template>().HasData(
                new Template { TemplateId = 1, Name = "Employee Profile"                 , Description = "Employee Profile" },
                new Template { TemplateId = 2, Name = "Employee Timesheet"               , Description = "Employee Timesheet" },
                new Template { TemplateId = 3, Name = "Employee Direct Deposit"          , Description = "Employee Direct Deposit" },
                new Template { TemplateId = 4, Name = "Employee Deductions/Contributions", Description = "Employee Deductions/Contributions" }
            );
            #endregion

            #region TemplateFields
            modelBuilder.Entity<TemplateFields>().HasData(
                new TemplateFields { TemplateFieldsId = 1 , TemplateId = 1, FieldName = "DayforceBranchNo"                    , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 2 , TemplateId = 1, FieldName = "DayforcePayrollNumber"               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 3 , TemplateId = 1, FieldName = "EmployeeNumber"                      , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 4 , TemplateId = 1, FieldName = "FirstName"                           , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 5 , TemplateId = 1, FieldName = "LastName"                            , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 6 , TemplateId = 1, FieldName = "MiddleInitial"                       , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 7 , TemplateId = 1, FieldName = "SIN"                                 , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 8 , TemplateId = 1, FieldName = "SinExpiryDate"                       , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 9 , TemplateId = 1, FieldName = "Street"                              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 10, TemplateId = 1, FieldName = "City"                                , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 11, TemplateId = 1, FieldName = "Province/State"                      , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 12, TemplateId = 1, FieldName = "Country"                             , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 13, TemplateId = 1, FieldName = "Postal/ZipCode"                      , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 14, TemplateId = 1, FieldName = "PhoneNumber"                         , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 15, TemplateId = 1, FieldName = "Phone2"                              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 16, TemplateId = 1, FieldName = "Phone3"                              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 17, TemplateId = 1, FieldName = "Email"                               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 18, TemplateId = 1, FieldName = "AdditionalEmail"                     , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 19, TemplateId = 1, FieldName = "PreferredLanguage"                   , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 20, TemplateId = 1, FieldName = "Gender"                              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 21, TemplateId = 1, FieldName = "BirthDate"                           , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 22, TemplateId = 1, FieldName = "FirstDayWorked"                      , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 23, TemplateId = 1, FieldName = "ROEFirstDayWorked"                   , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 24, TemplateId = 1, FieldName = "PayType"                             , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 25, TemplateId = 1, FieldName = "PayRate"                             , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 26, TemplateId = 1, FieldName = "StandardHoursPerPay"                 , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 27, TemplateId = 1, FieldName = "EmploymentType"                      , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 28, TemplateId = 1, FieldName = "ProvinceOfEmployment"                , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 29, TemplateId = 1, FieldName = "FederalTaxExemption"                 , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 30, TemplateId = 1, FieldName = "ProvincialTaxExemption"              , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 31, TemplateId = 1, FieldName = "TaxStatus"                           , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 32, TemplateId = 1, FieldName = "CPP/QPPStatus"                       , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 33, TemplateId = 1, FieldName = "EICategory"                          , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 34, TemplateId = 1, FieldName = "ProvincialPayroll/HealthTaxIndicator", TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 35, TemplateId = 1, FieldName = "ProvincialSafetyPlans"               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 36, TemplateId = 1, FieldName = "QPIP"                                , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 37, TemplateId = 1, FieldName = "QuebecHealthContribution"            , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 38, TemplateId = 1, FieldName = "QuebecConstruction"                  , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 39, TemplateId = 1, FieldName = "PositionReferenceCode"               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 40, TemplateId = 1, FieldName = "Department"                          , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 41, TemplateId = 1, FieldName = "PositionTitle"                       , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 42, TemplateId = 1, FieldName = "IDY"                                 , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 43, TemplateId = 1, FieldName = "IDZ"                                 , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 44, TemplateId = 1, FieldName = "IDX"                                 , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 45, TemplateId = 1, FieldName = "VacationPlan"                        , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 46, TemplateId = 1, FieldName = "VacationRate"                        , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 47, TemplateId = 1, FieldName = "AdditionalAccumulatorPlan"           , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 48, TemplateId = 1, FieldName = "AdditionalAccumulatorRate"           , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 49, TemplateId = 1, FieldName = "SickPayAccumulatorPlan"              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 50, TemplateId = 1, FieldName = "SickPayAccumulatorHoursPerDay"       , TemplateDataTypeId = 2 },                
                new TemplateFields { TemplateFieldsId = 51, TemplateId = 2, FieldName = "DayforceBranchNo"                    , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 52, TemplateId = 2, FieldName = "DayforcePayrollNumber"               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 53, TemplateId = 2, FieldName = "EmployeeNumber"                      , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 54, TemplateId = 2, FieldName = "ApplicablePeriodOfTime"              , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 55, TemplateId = 2, FieldName = "EarningCode"                         , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 56, TemplateId = 2, FieldName = "Hours"                               , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 57, TemplateId = 2, FieldName = "ExceptionRate"                       , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 58, TemplateId = 2, FieldName = "Amount"                              , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 59, TemplateId = 2, FieldName = "PositionReferenceCode"               , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 61, TemplateId = 2, FieldName = "DistributionIdDept"                  , TemplateDataTypeId = 2 },
                new TemplateFields { TemplateFieldsId = 62, TemplateId = 2, FieldName = "DistributionIDY"                     , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 63, TemplateId = 2, FieldName = "DistributionIDZ"                     , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 64, TemplateId = 2, FieldName = "DistributionIDX"                     , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 65, TemplateId = 2, FieldName = "ForWhichPayPeriod"                   , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 66, TemplateId = 2, FieldName = "VacationPayAccumulatorType"          , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 67, TemplateId = 2, FieldName = "VAPayoutForPayPeriodEnding"          , TemplateDataTypeId = 3 },
                new TemplateFields { TemplateFieldsId = 68, TemplateId = 2, FieldName = "AdditionalAccumulatorPayoutType"     , TemplateDataTypeId = 1 },
                new TemplateFields { TemplateFieldsId = 69, TemplateId = 2, FieldName = "WAPayoutForPayPeriodEnding"          , TemplateDataTypeId = 3 }
            );
            #endregion

            #region TemplateDatabaseMapping
            //modelBuilder.Entity<TemplateDatabaseMapping>().HasData(
            //    new TemplateDatabaseMapping { TemplateDatabaseMappingId = 1, TemplateFieldsId = 1, TemplateId = 1, Table = "Table1" , Field = "Field1"},
            //    new TemplateDatabaseMapping { TemplateDatabaseMappingId = 2, TemplateFieldsId = 2, TemplateId = 2, Table = "Table2" , Field = "Field2"},
            //    new TemplateDatabaseMapping { TemplateDatabaseMappingId = 3, TemplateFieldsId = 3, TemplateId = 3, Table = "Table3" , Field = "Field3"}
            //);
            #endregion

            #region ValidationRules
            modelBuilder.Entity<ValidationRule>().HasData(

            // DayforceBranchNo: required + regex
            new ValidationRule { TemplateId = 1, FieldName = "DayforceBranchNo", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "DayforceBranchNo", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(PP(1|2|3|4|7|9))$\"}" },

            // DayforcePayrollNumber: required + regex
            new ValidationRule { TemplateId = 1, FieldName = "DayforcePayrollNumber", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "DayforcePayrollNumber", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^.{4}$\"}" },

            // EmployeeNumber: required + regex
            new ValidationRule { TemplateId = 1, FieldName = "EmployeeNumber", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "EmployeeNumber", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^(?!000000000$)(?!0{3,5}[0-9]{4,6}$)(?!9999[0-9]{5}$)\\\\d{1,9}$\"}" },

            // FirstName: required + regex
            new ValidationRule { TemplateId = 1, FieldName = "FirstName", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "FirstName", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_ -.‘&\\/\\\\()@#]{1,25}$\"}" },

            // LastName: required + regex
            new ValidationRule { TemplateId = 1, FieldName = "LastName", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "LastName", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_ -.‘&\\/\\\\()@#]{1,25}$\"}" },

            // Middle Initial: regex
            new ValidationRule { TemplateId = 1, FieldName = "MiddleInitial", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_ -.‘&\\/\\\\()@#]{1,4}$\"}" },

            // SIN: regex
            new ValidationRule { TemplateId = 1, FieldName = "SIN", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^(?![08])\\\\d{3} \\\\d{3} \\\\d{3}$\"}" },

            // SIN Expiry: Conditional Requirement + DateFormat + ExpiryDate
            new ValidationRule { TemplateId = 1, FieldName = "SINExpiryDate", RuleType = RuleType.DateFormat, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "SINExpiryDate", RuleType = RuleType.ExpiryDate, ConfigJson = "{}" },

            // Street: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Street", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_ -.‘,:&\\/\\\\()#]{1,34}$\"}" },

            // City: Regex
            new ValidationRule { TemplateId = 1, FieldName = "City", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_ -.‘,:&\\/\\\\()#]{1,18}$\"}" },

            // Province/State: Regex
            new ValidationRule
            {
                TemplateId = 1,
                FieldName = "Province/State",
                RuleType = RuleType.Regex,
                ConfigJson = "{\"Pattern\":\"^(AB|BC|MB|NB|NL|NN|NS|NT|ON|PE|QC|SK|YT|US|OT|AL|AK|AZ|AR|CA|CO|CT" +
                                            "|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY" +
                                            "|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)$\"}"
            },

            // Country: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Country", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(CAN|USA)$\"}" },

            // Postal/ZipCode: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Postal/ZipCode", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^([ABCEGHJ-NPRSTVXY]\\\\d[ABCEGHJ-NPRSTV-Z][ ]?\\\\d[ABCEGHJ-NPRSTV-Z]\\\\d|\\\\d{5}?)$\"}" },

            // PhoneNumber: Regex
            new ValidationRule { TemplateId = 1, FieldName = "PhoneNumber", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{10}|\\\\d{3}-\\\\d{3}-\\\\d{4})$\"}" },

            // Phone2: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Phone2", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{10}|\\\\d{3}-\\\\d{3}-\\\\d{4})$\"}" },

            // Phone3: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Phone3", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{10}|\\\\d{3}-\\\\d{3}-\\\\d{4})$\"}" },

            // Email: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Email", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_\\\\-.@]{1,80}$\"}" },

            // Additional Email: Regex
            new ValidationRule { TemplateId = 1, FieldName = "AdditionalEmail", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9_\\\\-.@]{1,80}$\"}" },

            // Preferred Language: Regex
            new ValidationRule { TemplateId = 1, FieldName = "PreferredLanguage", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(E|F)$\"}" },

            // Gender: Regex
            new ValidationRule { TemplateId = 1, FieldName = "Gender", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(M|F|U)$\"}" },

            // BirthDate: DateFormat
            new ValidationRule { TemplateId = 1, FieldName = "BirthDate", RuleType = RuleType.DateFormat, ConfigJson = "{}" },

            // FirstDayWorked: DateFormat
            new ValidationRule { TemplateId = 1, FieldName = "FirstDayWorked", RuleType = RuleType.DateFormat, ConfigJson = "{}" },

            // ROEFirstDayWorked: DateFormat
            new ValidationRule { TemplateId = 1, FieldName = "ROEFirstDayWorked", RuleType = RuleType.DateFormat, ConfigJson = "{}" },

            //Pay Type: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "PayType", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "PayType", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(S|L|H|V|W|A|C|K|1|2)$\"}" },

            // Pay Rate: Regex
            new ValidationRule { TemplateId = 1, FieldName = "PayRate", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,6}.\\\\d{2})$\"}" },

            // Standard Hours per pay: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "StandardHoursPerPay", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "StandardHoursPerPay", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,3}.\\\\d{2})$\"}" },

            // Employment Type: Regex
            new ValidationRule { TemplateId = 1, FieldName = "EmploymentType", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(F|P)$\"}" },

            // Province of Employment: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "ProvinceOfEmployment", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule
            {
                TemplateId = 1,
                FieldName = "ProvinceOfEmployment",
                RuleType = RuleType.Regex,
                ConfigJson = "{\"Pattern\":\"^(AB|BC|MB|NB|NL|NN|NS|NT|ON|PE|QC|SK|YT|US|OT|AL|AK|AZ|AR|CA|CO|CT" +
                                            "|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY" +
                                            "|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)$\"}"
            },

            // Federal Tax Exemption: Regex
            new ValidationRule { TemplateId = 1, FieldName = "FederalTaxExemption", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,5})$\"}" },

            // Provincial Tax Exemption: Regex
            new ValidationRule { TemplateId = 1, FieldName = "ProvincialTaxExemption", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,5})$\"}" },

            // Tax Status: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "TaxStatus", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "TaxStatus", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(1|X|F|M|A|I|P|N|V)$\"}" },

            // CPP/QPP Status: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "CPP/QPPStatus", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "CPP/QPPStatus", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(X|0)$\"}" },

            // EI Category: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "EICategory", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "EICategory", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(P|0|2|N|X|B|E|J)$\"}" },

            // Provincial Payroll/Health Tax Indicator: Regex
            new ValidationRule { TemplateId = 1, FieldName = "ProvincialPayroll/HealthTaxIndicator", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{1}$\"}" },

            // Provincial Safety Plans: Regex
            new ValidationRule { TemplateId = 1, FieldName = "ProvincialSafetyPlans", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{1}$\"}" },

            // QPIP: Regex
            new ValidationRule { TemplateId = 1, FieldName = "QPIP", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(0|1)$\"}" },

            // Quebec Health Contribution: Regex
            new ValidationRule { TemplateId = 1, FieldName = "QuebecHealthContribution", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(0|N)$\"}" },

            // Quebec Construction: Regex
            new ValidationRule { TemplateId = 1, FieldName = "QuebecConstruction", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(0|Y)$\"}" },

            // Position Reference Code: Regex
            new ValidationRule { TemplateId = 1, FieldName = "PositionReferenceCode", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9&-\\/\\\\.',:()_#;]{1,10}$\"}" },

            // Department: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "Department", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "Department", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{3}$\"}" },

            // Position Title: Regex
            new ValidationRule { TemplateId = 1, FieldName = "PositionTitle", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9&-\\/\\\\.',:()_#;]{1,30}$\"}" },

            // IDY: Regex
            new ValidationRule { TemplateId = 1, FieldName = "IDY", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9'.-\\/\\\\#@&*+]{1,2}$\"}" },

            // IDZ: Regex
            new ValidationRule { TemplateId = 1, FieldName = "IDZ", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9'.-\\/\\\\#@&*+]{1,2}$\"}" },

            // IDX: Regex
            new ValidationRule { TemplateId = 1, FieldName = "IDX", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\": \"^[a-zA-Z0-9'.-\\/\\\\#@&*+]{1,2}$\"}" },

            // Vacation Plan: Required + Regex
            new ValidationRule { TemplateId = 1, FieldName = "VacationPlan", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "VacationPlan", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{1}$\"}" },

            // Vacation Rate: Conditional Requirement + Regex
            //new ValidationRule { TemplateId = 1, FieldName = "VacationRate", RuleType = RuleType.Required, ConfigJson = "{}" },
            new ValidationRule { TemplateId = 1, FieldName = "VacationRate", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,2}.\\\\d{2})$\"}" },

            // Additional Accumulator Plan: Regex
            new ValidationRule { TemplateId = 1, FieldName = "AdditionalAccumulatorPlan", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{1}$\"}" },

            // Additional Accumulator Rate: Conditional Requirement + Regex
            new ValidationRule { TemplateId = 1, FieldName = "AdditionalAccumulatorRate", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,2}.\\\\d{2})$\"}" },

            // Sick Pay Accumulator Plan: Regex
            new ValidationRule { TemplateId = 1, FieldName = "SickPayAccumulatorPlan", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^[a-zA-Z0-9]{1}$\"}" },

            // Sick Pay Accumulator Hours Per Pay: Conditional Requirement + Regex
            new ValidationRule { TemplateId = 1, FieldName = "SickPayAccumulatorHoursPerPay", RuleType = RuleType.Regex, ConfigJson = "{\"Pattern\":\"^(\\\\d{1,2}.\\\\d{2})$\"}" }

            );
            #endregion

            #region ProcessStep
            modelBuilder.Entity<ProcessStep>().HasData(
                new ProcessStep { ProcessStepId = 1, Name = "Tansforming", Description = "ProcessStep1" },
                new ProcessStep { ProcessStepId = 2, Name = "Mapping"    , Description = "ProcessStep2" },
                new ProcessStep { ProcessStepId = 3, Name = "Validating" , Description = "ProcessStep3" }
            );
            #endregion

            #region ImportHistory
            modelBuilder.Entity<ImportHistory>().HasData(
            );
            #endregion

            #region ImportDetails
            modelBuilder.Entity<ImportDetails>().HasData(
            );
            #endregion
        }

    }
}
