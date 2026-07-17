using MoDocument.Domain.Entities;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.Interfaces
{
    public interface IImportDetailsRepository
    {
        Task<ImportDetails?> GetByIdAsync(int id);
        Task<List<ImportDetails>> ListAsync();
        Task<ImportDetails> AddAsync(ImportDetails entity);
        Task<ImportDetails> UpdateAsync(ImportDetails entity);
        Task DeleteAsync(int id);
    }
}
