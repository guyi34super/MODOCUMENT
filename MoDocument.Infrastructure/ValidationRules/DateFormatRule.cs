using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Infrastructure.ValidationRules
{
    public class DateFormatRule : IValidationRule
    {
        public bool CanHandle(RuleType type) => type == RuleType.DateFormat;

        public ValidationError? Validate(int row, string fieldName, string rawValue, string configJson)
        {
            if (!DateTime.TryParseExact(rawValue, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsedDate))
            {
                return new ValidationError(row, fieldName, rawValue, "Date must be in the format YYYY-MM-DD");
            }
            return null;
        }
    }
}
