using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using MoDocument.Domain.Entities;

namespace MoDocument.Application.Utils
{
    public class RegexValidationErrorMessage
    {
        public static ValidationError GetRegexValidationErrorMessage(int row, string fieldName, string rawValue)
        {
            switch (fieldName)
            {
                case "DayforceBranchNo":
                    return new ValidationError(row, fieldName, rawValue, "Dayforce Branch Number must be one of PP1,PP2,PP3,PP4,PP7 or PP9.");
                case "DayforcePayrollNumber":
                    return new ValidationError(row, fieldName, rawValue, "Dayforce Payroll Number must be exactly 4 characters long and only valid payroll numbers assigned by Dayforce.");
                case "EmployeeNumber":
                    return new ValidationError(row, fieldName, rawValue, "Employee Number must be no longer than 9 digits long and the following employee numbers are not allowed: 000000000, 000009961-000009999, 999900000-999999999 ");
                case "FirstName":
                    return new ValidationError(row, fieldName, rawValue, "First Name must be no longer than 25 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ");
                case "LastName":
                    return new ValidationError(row, fieldName, rawValue, "Last Name must be no longer than 25 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ");
                case "MiddleInitial":
                    return new ValidationError(row, fieldName, rawValue, "Middle Initial must be no longer than 4 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ");
                case "SIN":
                    return new ValidationError(row, fieldName, rawValue, "SIN must have 9 digits and cannot start with a 0 or 8");
                case "Street":
                    return new ValidationError(row, fieldName, rawValue, "Street name cannot exceed 34 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) # ");
                case "City":
                    return new ValidationError(row, fieldName, rawValue, "City name cannot exceed 18 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) # ");
                case "Province/State":
                    return new ValidationError(row, fieldName, rawValue, "Province/State must be a valid Canadian Province postal abbreviation/US State Code");
                case "Country":
                    return new ValidationError(row, fieldName, rawValue, "Must be either CAN or USA");
                case "PhoneNumber":
                    return new ValidationError(row, fieldName, rawValue, "Phone Number must be in the format 9999999999 or 999-999-9999");
                case "Phone2":
                    return new ValidationError(row, fieldName, rawValue, "Phone2 must be in the format 9999999999 or 999-999-9999");
                case "Phone3":
                    return new ValidationError(row, fieldName, rawValue, "Phone3 must be in the format 9999999999 or 999-999-9999");
                case "Email":
                    return new ValidationError(row, fieldName, rawValue, "Email must not exceed 80 characters and only the following special characters are allowed: _ @ - .");
                case "AdditionalEmail":
                    return new ValidationError(row, fieldName, rawValue, "Additional Email must not exceed 80 characters and only the following special characters are allowed: _ @ - .");
                case "PreferredLanguage":
                    return new ValidationError(row, fieldName, rawValue, "Preferred Language must be either E or F");
                case "Gender":
                    return new ValidationError(row, fieldName, rawValue, "Gender must be either M, F or U");
                case "PayType":
                    return new ValidationError(row, fieldName, rawValue, "Pay Type must be either S, L, H, V, W, A, C, K, 1 or 2 as per PowerPay descriptions");
                case "PayRate":
                    return new ValidationError(row, fieldName, rawValue, "Pay Rate must be in the format of 999999.99");
                case "StandardHoursPerPay":
                    return new ValidationError(row, fieldName, rawValue, "Standard Hours Per Pay must be in the format of 0.01 to 999.99");
                case "EmploymentType":
                    return new ValidationError(row, fieldName, rawValue, "Employment type must be either F or P (Full-time or Part-time)");
                case "ProvinceOfEmployment":
                    return new ValidationError(row, fieldName, rawValue, "Province of Employment must be a valid Canadian Province postal abbreviation/US State Code");
                case "FederalTaxExemption":
                    return new ValidationError(row, fieldName, rawValue, "Federal Tax Exemption must be in the format of 99999");
                case "ProvincialTaxExemption":
                    return new ValidationError(row, fieldName, rawValue, "Provincial Tax Exemption must be in the format of 99999");
                case "TaxStatus":
                    return new ValidationError(row, fieldName, rawValue, "Tax Status must be either 1, X, F, M, A, I, P, N or V as per PowerPay descriptions");
                case "CPP/QPPStatus":
                    return new ValidationError(row, fieldName, rawValue, "CPP/QPP Status must be either 0(zero) or X as per PowerPay descriptions");
                case "EICategory":
                    return new ValidationError(row, fieldName, rawValue, "EI Category must be either P, 0(zero), 2, N, X, B, E or J as per PowerPay descriptions");
                case "ProvincialPayroll/HealthTaxIndicator":
                    return new ValidationError(row, fieldName, rawValue, "ProvincialPayroll/HealthTaxIndicator must not exceed 1 character and is based on province of employment in PowerPay");
                case "ProvincialSafetyPlans":
                    return new ValidationError(row, fieldName, rawValue, "Provincial Safety Plans must not exceed 1 character and typically corresponds to plans set up for the payroll if the employee's province of employment is Nova Scotia or Quebec. May contain a value for other provinces");
                case "QPIP":
                    return new ValidationError(row, fieldName, rawValue, "QPIP must be either 0(zero) or 1 as per PowerPay descriptions");
                case "QuebecHealthContribution":
                    return new ValidationError(row, fieldName, rawValue, "Quebec Health Contribution must be either 0(zero) or N");
                case "QuebecConstruction":
                    return new ValidationError(row, fieldName, rawValue, "Quebec Construction (CSST Weekly Maximum Assessable Earnings) must be either 0(zero) or Y");
                case "PositionReferenceCode":
                    return new ValidationError(row, fieldName, rawValue, "Position Reference Code must not exceed 10 characters and must correspond to a position already set up in PowerPay application of the payroll (Compensation module is required) and can only contain the following special characters: & - / \\ . ‘ , : ( ) _ # ; ");
                case "Department":
                    return new ValidationError(row, fieldName, rawValue, "Department must not exceed 3 characters and must be in the format of 999 (default code: 100)");
                case "PositionTitle":
                    return new ValidationError(row, fieldName, rawValue, "Position Title must not exceed 30 characters and can only contain the following special characters: & - / \\ . ' , : ( ) _ # and ; ");
                case "IDY":
                    return new ValidationError(row, fieldName, rawValue, "ID Y must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ");
                case "IDZ":
                    return new ValidationError(row, fieldName, rawValue, "ID Z must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ");
                case "IDX":
                    return new ValidationError(row, fieldName, rawValue, "ID X must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ");
                case "VacationPlan":
                    return new ValidationError(row, fieldName, rawValue, "Vacation Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll");
                case "VacationRate":
                    return new ValidationError(row, fieldName, rawValue, "Vacation Rate must be in the format of 99.99. Not required if vacation plan is X.");
                case "AdditionalAccumulatorPlan":
                    return new ValidationError(row, fieldName, rawValue, "Additional Accumulator Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll");
                case "AdditionalAccumulatorRate":
                    return new ValidationError(row, fieldName, rawValue, "Additional Accumulator Rate must be in the format of 99.99. Not required if Additional Accumulator plan is X or if used for previous Vacation.");
                case "SickPayAccumulatorPlan":
                    return new ValidationError(row, fieldName, rawValue, "Sick Pay Accumulator Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll");
                case "SickPayAccumulatorHoursPerPay":
                    return new ValidationError(row, fieldName, rawValue, "Sick Pay Accumulator Hours per pay must be in the format of 99.99. Not required if Sick Pay Accumulator Plan is X or if used for previous Vacation.");
                default:
                    return new ValidationError(row, fieldName, rawValue, $"Validation error for {fieldName}.");
            }
        }

    }
}
