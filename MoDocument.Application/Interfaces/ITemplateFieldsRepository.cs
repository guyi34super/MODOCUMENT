using MoDocument.Domain.Entities;

namespace MoDocument.API.Controllers
{
    public interface ITemplateFieldsRepository
    {
        Task<TemplateFields?> GetByIdAsync(int id);
        Task<List<TemplateFields>> ListAsync();
        Task<TemplateFields> AddAsync(TemplateFields entity);
        Task<TemplateFields> UpdateAsync(TemplateFields entity);
        Task DeleteAsync(int id);
    }
}