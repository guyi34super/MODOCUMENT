using System.Text.Json;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Infrastructure.ValidationRules
{
    public class RangeRule : IValidationRule
    {
        private class Config { public double Min { get; set; } 
        public double Max { get; set; } }

        public bool CanHandle(RuleType type) => type == RuleType.Range;

        public ValidationError? Validate(int row, string fieldName, string rawValue, string configJson)
        {
            if (!double.TryParse(rawValue, out var value))
                return new ValidationError(row, fieldName, rawValue, "Value is not a number."); 
            var cfg = JsonSerializer.Deserialize<Config>(configJson)!;
            if (value < cfg.Min || value > cfg.Max)
                return new ValidationError(row, fieldName, rawValue, $"Must be between {cfg.Min} and {cfg.Max}.");
            return null;
        }
    }
}
