using System.Collections.Generic;
using System.Threading.Tasks;
using MoDocument.Domain.Entities;

namespace MoDocument.Domain.Interfaces
{
    public interface IValidationRuleRepository
    {
        Task<IEnumerable<ValidationRule>> GetRulesByTemplateAsync(int templateId);
    }
}
