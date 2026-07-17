package com.modocument.application.validation;

import com.modocument.domain.model.ValidationError;

/**
 * Human-readable, payroll-domain error messages returned when a field fails
 * its regex validation. Message text ported verbatim from the original
 * backend (Dayforce/PowerPay compliance wording).
 */
public final class RegexValidationErrorMessages {

    private RegexValidationErrorMessages() {
    }

    public static ValidationError getRegexValidationErrorMessage(int row, String fieldName, String rawValue) {
        String message = switch (fieldName) {
            case "DayforceBranchNo" ->
                    "Dayforce Branch Number must be one of PP1,PP2,PP3,PP4,PP7 or PP9.";
            case "DayforcePayrollNumber" ->
                    "Dayforce Payroll Number must be exactly 4 characters long and only valid payroll numbers assigned by Dayforce.";
            case "EmployeeNumber" ->
                    "Employee Number must be no longer than 9 digits long and the following employee numbers are not allowed: 000000000, 000009961-000009999, 999900000-999999999 ";
            case "FirstName" ->
                    "First Name must be no longer than 25 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ";
            case "LastName" ->
                    "Last Name must be no longer than 25 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ";
            case "MiddleInitial" ->
                    "Middle Initial must be no longer than 4 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) @ # ";
            case "SIN" ->
                    "SIN must have 9 digits and cannot start with a 0 or 8";
            case "Street" ->
                    "Street name cannot exceed 34 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) # ";
            case "City" ->
                    "City name cannot exceed 18 characters and only the following special characters are allowed: _ - . ‘ & / \\ ( ) # ";
            case "Province/State" ->
                    "Province/State must be a valid Canadian Province postal abbreviation/US State Code";
            case "Country" ->
                    "Must be either CAN or USA";
            case "PhoneNumber" ->
                    "Phone Number must be in the format 9999999999 or 999-999-9999";
            case "Phone2" ->
                    "Phone2 must be in the format 9999999999 or 999-999-9999";
            case "Phone3" ->
                    "Phone3 must be in the format 9999999999 or 999-999-9999";
            case "Email" ->
                    "Email must not exceed 80 characters and only the following special characters are allowed: _ @ - .";
            case "AdditionalEmail" ->
                    "Additional Email must not exceed 80 characters and only the following special characters are allowed: _ @ - .";
            case "PreferredLanguage" ->
                    "Preferred Language must be either E or F";
            case "Gender" ->
                    "Gender must be either M, F or U";
            case "PayType" ->
                    "Pay Type must be either S, L, H, V, W, A, C, K, 1 or 2 as per PowerPay descriptions";
            case "PayRate" ->
                    "Pay Rate must be in the format of 999999.99";
            case "StandardHoursPerPay" ->
                    "Standard Hours Per Pay must be in the format of 0.01 to 999.99";
            case "EmploymentType" ->
                    "Employment type must be either F or P (Full-time or Part-time)";
            case "ProvinceOfEmployment" ->
                    "Province of Employment must be a valid Canadian Province postal abbreviation/US State Code";
            case "FederalTaxExemption" ->
                    "Federal Tax Exemption must be in the format of 99999";
            case "ProvincialTaxExemption" ->
                    "Provincial Tax Exemption must be in the format of 99999";
            case "TaxStatus" ->
                    "Tax Status must be either 1, X, F, M, A, I, P, N or V as per PowerPay descriptions";
            case "CPP/QPPStatus" ->
                    "CPP/QPP Status must be either 0(zero) or X as per PowerPay descriptions";
            case "EICategory" ->
                    "EI Category must be either P, 0(zero), 2, N, X, B, E or J as per PowerPay descriptions";
            case "ProvincialPayroll/HealthTaxIndicator" ->
                    "ProvincialPayroll/HealthTaxIndicator must not exceed 1 character and is based on province of employment in PowerPay";
            case "ProvincialSafetyPlans" ->
                    "Provincial Safety Plans must not exceed 1 character and typically corresponds to plans set up for the payroll if the employee's province of employment is Nova Scotia or Quebec. May contain a value for other provinces";
            case "QPIP" ->
                    "QPIP must be either 0(zero) or 1 as per PowerPay descriptions";
            case "QuebecHealthContribution" ->
                    "Quebec Health Contribution must be either 0(zero) or N";
            case "QuebecConstruction" ->
                    "Quebec Construction (CSST Weekly Maximum Assessable Earnings) must be either 0(zero) or Y";
            case "PositionReferenceCode" ->
                    "Position Reference Code must not exceed 10 characters and must correspond to a position already set up in PowerPay application of the payroll (Compensation module is required) and can only contain the following special characters: & - / \\ . ‘ , : ( ) _ # ; ";
            case "Department" ->
                    "Department must not exceed 3 characters and must be in the format of 999 (default code: 100)";
            case "PositionTitle" ->
                    "Position Title must not exceed 30 characters and can only contain the following special characters: & - / \\ . ' , : ( ) _ # and ; ";
            case "IDY" ->
                    "ID Y must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ";
            case "IDZ" ->
                    "ID Z must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ";
            case "IDX" ->
                    "ID X must not exceed 2 characters and can only contain the following special characters: / ' . - # @ & * + and \\ ";
            case "VacationPlan" ->
                    "Vacation Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll";
            case "VacationRate" ->
                    "Vacation Rate must be in the format of 99.99. Not required if vacation plan is X.";
            case "AdditionalAccumulatorPlan" ->
                    "Additional Accumulator Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll";
            case "AdditionalAccumulatorRate" ->
                    "Additional Accumulator Rate must be in the format of 99.99. Not required if Additional Accumulator plan is X or if used for previous Vacation.";
            case "SickPayAccumulatorPlan" ->
                    "Sick Pay Accumulator Plan must not exceed 1 character and must correspond to plans set up in PowerPay application for the payroll";
            case "SickPayAccumulatorHoursPerPay" ->
                    "Sick Pay Accumulator Hours per pay must be in the format of 99.99. Not required if Sick Pay Accumulator Plan is X or if used for previous Vacation.";
            default -> "Validation error for " + fieldName + ".";
        };
        return new ValidationError(row, fieldName, rawValue, message);
    }
}
