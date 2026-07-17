using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Infrastructure.ValidationRules
{
    public class RequiredRule : IValidationRule
    {
        public bool CanHandle(RuleType type) => type == RuleType.Required;

        public ValidationError? Validate(int row, string fieldName, string rawValue, string configJson)
        {
            if (string.IsNullOrWhiteSpace(rawValue))
                return new ValidationError(row, fieldName, rawValue, "Value is required.");
            return null;
        }
    }
}
