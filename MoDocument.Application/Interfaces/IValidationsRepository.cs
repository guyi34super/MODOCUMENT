using MoDocument.Domain.Entities;

namespace MoDocument.API.Controllers
{
    public interface IValidationsRepository
    {
        Task<Validations?> GetByIdAsync(int id);
        Task<List<Validations>> ListAsync();
        Task<Validations> AddAsync(Validations entity);
        Task<Validations> UpdateAsync (Validations entity);
        Task DeleteAsync(int id);
    }
}