using System.Text.Json;
using System.Text.RegularExpressions;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Enums;
using MoDocument.Domain.Interfaces;
using MoDocument.Application.Utils;

namespace MoDocument.Infrastructure.ValidationRules
{
    public class RegexRule : IValidationRule
    {
        private class Config { public string Pattern { get; set; } = ""; }

        public bool CanHandle(RuleType type) => type == RuleType.Regex;

        public ValidationError? Validate(int row, string fieldName, string rawValue, string configJson)
        {
            Config cfg;
            try
            {
                cfg = JsonSerializer.Deserialize<Config>(configJson)!;
            }
            catch (JsonException)
            {
                return new ValidationError(row, fieldName, rawValue, "Invalid validation configuration.");
            }

            if (rawValue == null || rawValue.Trim() == string.Empty)
            {
                return null;
            }

            var regex = new Regex(cfg.Pattern, RegexOptions.Compiled, TimeSpan.FromMilliseconds(200));
            if (!regex.IsMatch(rawValue))
            {
                return RegexValidationErrorMessage.GetRegexValidationErrorMessage(row, fieldName, rawValue);
            }
            else
            {
                return null;
            }
        }
    }
}
