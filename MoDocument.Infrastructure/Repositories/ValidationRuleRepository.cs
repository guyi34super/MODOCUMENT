using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using MoDocument.Domain.Entities;
using MoDocument.Application.Interfaces;
using MoDocument.Infrastructure.Persistence;
using MoDocument.Domain.Interfaces;

namespace MoDocument.Infrastructure.Repositories
{
    public class ValidationRuleRepository : IValidationRuleRepository
    {
        private readonly AppDbContext _db;
        public ValidationRuleRepository(AppDbContext db) => _db = db;

        public async Task<IEnumerable<ValidationRule>> GetRulesByTemplateAsync(int templateId)
        {
            return await _db.ValidationRules
                .AsNoTracking()
                .Where(r => r.TemplateId == templateId)
                .ToListAsync();
        }
    }
}
