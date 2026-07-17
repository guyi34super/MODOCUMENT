using MoDocument.Domain.Enums;

namespace MoDocument.Domain.Entities
{
    public class ValidationRule
    {
        public int TemplateId { get; set; }
        public string FieldName { get; set; } = "";
        public RuleType RuleType { get; set; }
        public string ConfigJson { get; set; } = "";
    }
}
