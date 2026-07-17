using MoDocument.Domain.Entities;

namespace MoDocument.API.Controllers
{
    public interface IProcessStepRepository
    {
        Task<ProcessStep?> GetByIdAsync(int id);
        Task<List<ProcessStep>> ListAsync();
        Task AddAsync(ProcessStep entity);
        Task<ProcessStep> UpdateAsync(ProcessStep entity);
        Task DeleteAsync(int id);
    }
}