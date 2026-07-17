using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Infrastructure.ValidationRules
{
    public class ExpiryDateRule : IValidationRule
    {
        public bool CanHandle(RuleType type) => type == RuleType.ExpiryDate;

        public ValidationError? Validate(int row, string fieldName, string rawValue, string configJson)
        {
            if (DateTime.TryParse(rawValue, out var value))
            {
                if (value == DateTime.Now || value < DateTime.Now)
                {
                    return new ValidationError(row, fieldName, rawValue, "Expiry date must be in the future.");
                }
                else
                {
                    return null; // Valid expiry date
                }

            }
            return null;
        }
    }
}
