using MoDocument.Domain.Entities;

namespace MoDocument.API.Controllers
{
    public interface IImportHistoryRepository
    {
        Task<ImportHistory?> GetByIdAsync(int id);
        Task<List<ImportHistory>> ListAsync();
        Task<ImportHistory> AddAsync(ImportHistory entity);
        Task<ImportHistory> UpdateAsync(ImportHistory entity);
        Task DeleteAsync(int id);
		Task<ImportHistory?> GetByFileNameAsync(string filename);
	}
}