using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;

namespace MoDocument.Domain.Interfaces
{
    public interface IValidationRule
    {
        bool CanHandle(RuleType type);
        ValidationError? Validate(int row, string fieldName, string rawValue, string configJson);
    }
}
