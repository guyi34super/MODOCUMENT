using MoDocument.Domain.Entities;

namespace MoDocument.API.Controllers
{
    public interface ITemplateRepository
    {
        Task<Template?> GetByIdAsync(int id);
        Task<List<Template>> ListAsync();
        Task AddAsync(Template entity);
        Task<Template> UpdateAsync(Template entity);
        Task DeleteAsync(int id);
    }
}