using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.Infrastructure.Persistence;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using MoDocument.API.Controllers;

namespace MoDocument.Infrastructure.Repositories
{
    public class ImportDetailsRepository : IImportDetailsRepository
    {
        private readonly AppDbContext _db;
        public ImportDetailsRepository(AppDbContext db) => _db = db;

        public async Task<List<ImportDetails>> ListAsync() =>
            await _db.ImportDetails
                     .AsNoTracking()
                     .ToListAsync();

        public async Task<ImportDetails?> GetByIdAsync(int id) =>
            await _db.ImportDetails.FindAsync(id);

        public async Task<ImportDetails> AddAsync(ImportDetails entity)
        {
            var importHistory = await _db.ImportHistory.FirstOrDefaultAsync(x => x.ImportHistoryId.Equals(entity.ImportHistoryId));
            var processStep = await _db.ProcessStep.FirstOrDefaultAsync(x => x.ProcessStepId.Equals(entity.ProcessStepId));


            if (importHistory == null || processStep == null)
                return new ImportDetails();

            entity.ImportHistory = importHistory;
            entity.ProcessStep = processStep;
            var result = await _db.ImportDetails.AddAsync(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new ImportDetails();
        }

        public async Task<ImportDetails> UpdateAsync(ImportDetails entity)
        {
            var importHistory = await _db.ImportHistory.FirstOrDefaultAsync(x => x.ImportHistoryId.Equals(entity.ImportHistoryId));
            var processStep = await _db.ProcessStep.FirstOrDefaultAsync(x => x.ProcessStepId.Equals(entity.ProcessStepId));

            if (importHistory == null || processStep == null)
                return new ImportDetails();

            entity.ImportHistory = importHistory;
            entity.ProcessStep = processStep;
            var result = _db.ImportDetails.Update(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new ImportDetails();
        }

        public async Task DeleteAsync(int id)
        {
            var entity = await _db.ImportDetails.FindAsync(id);
            if (entity != null)
            {
                _db.ImportDetails.Remove(entity);
                await _db.SaveChangesAsync();
            }
        }
    }
}
