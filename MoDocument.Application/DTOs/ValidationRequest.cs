using System.Collections.Generic;

namespace MoDocument.Application.DTOs
{
    public class ValidationRequest
    {
        public int TemplateId { get; set; }
        public List<Dictionary<string, string>> Rows { get; set; } = new();
    }
}
