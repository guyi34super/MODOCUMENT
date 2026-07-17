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
	public class ImportHistoryRepository : IImportHistoryRepository
	{
		private readonly AppDbContext _db;
		public ImportHistoryRepository(AppDbContext db) => _db = db;

		public async Task<List<ImportHistory>> ListAsync() =>
			await _db.ImportHistory
					.AsNoTracking()
					 .ToListAsync();

		public async Task<ImportHistory?> GetByIdAsync(int id) =>
			await _db.ImportHistory.FindAsync(id);

		public async Task<ImportHistory> AddAsync(ImportHistory entity)
		{
			var template = await _db.Template.FirstOrDefaultAsync(x => x.TemplateId.Equals(entity.TemplateId));

			if (template == null)
				return new ImportHistory();


			entity.Template = template;
			var result = await _db.ImportHistory.AddAsync(entity);
			return await _db.SaveChangesAsync() > 0 ? result.Entity : new ImportHistory();
		}

		public async Task<ImportHistory> UpdateAsync(ImportHistory entity)
		{
			var template = await _db.Template.FirstOrDefaultAsync(x => x.TemplateId.Equals(entity.TemplateId));

			if (template == null)
				return new ImportHistory();


			entity.Template = template;
			var result = _db.ImportHistory.Update(entity);
			return await _db.SaveChangesAsync() > 0 ? result.Entity : new ImportHistory();
		}

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.ImportHistory.FindAsync(id);
			if (entity != null)
			{
				_db.ImportHistory.Remove(entity);
				await _db.SaveChangesAsync();
			}
		}

		// create a get by file name
		public async Task<ImportHistory?> GetByFileNameAsync(string fileName)
		{
			return await _db.ImportHistory
				.AsNoTracking()
				.FirstOrDefaultAsync(x => x.FileName.Equals(fileName, StringComparison.OrdinalIgnoreCase));
		}
	}
}
