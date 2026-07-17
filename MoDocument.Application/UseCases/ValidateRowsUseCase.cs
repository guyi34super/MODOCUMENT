using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using MoDocument.Application.DTOs;
using MoDocument.Domain.Entities;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Application.UseCases
{
    public class ValidateRowsUseCase : IValidateRowsUseCase
    {
        private readonly IValidationRuleRepository _repo;
        private readonly IEnumerable<IValidationRule> _validators;

        public ValidateRowsUseCase(
            IValidationRuleRepository repo,
            IEnumerable<IValidationRule> validators)
        {
            _repo = repo;
            _validators = validators;
        }

        public async Task<List<RowValidationResult>> HandleAsync(ValidationRequest request)
        {
            var rules = await _repo.GetRulesByTemplateAsync(request.TemplateId);
            var results = new List<RowValidationResult>();

            for (int i = 0; i < request.Rows.Count; i++)
            {
                var rowNum = i + 1;
                var row = request.Rows[i];

                var rowResults = new Dictionary<string, RowValidationResult>();

                var groupedRules = rules.GroupBy(r => r.FieldName);

                foreach (var fieldRules in groupedRules)
                {
                    var fieldName = fieldRules.Key;
                    row.TryGetValue(fieldName, out var raw);
                    var rawValue = raw ?? string.Empty;

                    string? firstError = null;

                    foreach (var rule in fieldRules)
                    {
                        var validator = _validators.FirstOrDefault(v => v.CanHandle(rule.RuleType));
                        if (validator == null) continue;

                        var error = validator.Validate(rowNum, fieldName, rawValue, rule.ConfigJson);

                        if (error != null)
                        {
                            firstError = error.ErrorMessage;
                            break; // Only keep first error for this field
                        }
                    }

                    results.Add(new RowValidationResult(rowNum, fieldName, rawValue, firstError));
                }
            }

            return results;


            //    var rules = await _repo.GetRulesByTemplateAsync(request.TemplateId);
            //    var results = new List<RowValidationResult>();

            //    for (int i = 0; i < request.Rows.Count; i++)
            //    {
            //        var rowNum = i + 1;
            //        var row = request.Rows[i];

            //        foreach (var rule in rules)
            //        {
            //            row.TryGetValue(rule.FieldName, out var raw);
            //            var validator = _validators.First(v => v.CanHandle(rule.RuleType));
            //            var error = validator.Validate(rowNum, rule.FieldName, raw ?? string.Empty, rule.ConfigJson);

            //            if (error != null)
            //            {
            //                results.Add(new RowValidationResult(rowNum, rule.FieldName, raw ?? string.Empty, error.ErrorMessage));
            //            }
            //            else
            //            {
            //                results.Add(new RowValidationResult(rowNum, rule.FieldName, raw ?? string.Empty));
            //            }
            //        }
            //    }

            //    return results;
        }
    }
}
