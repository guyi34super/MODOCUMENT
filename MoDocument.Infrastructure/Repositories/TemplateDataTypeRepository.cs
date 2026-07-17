using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.Infrastructure.Persistence;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Http.HttpResults;
namespace MoDocument.Infrastructure.Repositories
{
	public class TemplateDataTypeRepository : ITemplateDataTypeRepository
	{
		private readonly AppDbContext _db;
		public TemplateDataTypeRepository(AppDbContext db) => _db = db;

		public async Task<TemplateDataType?> GetByIdAsync(int id) =>
			await _db.TemplateDataTypes.FindAsync(id);

		public async Task<List<TemplateDataType>> ListAsync() =>
			await _db.TemplateDataTypes
					.AsNoTracking()
					 .ToListAsync();

		public async Task AddAsync(TemplateDataType entity)
		{
			_db.TemplateDataTypes.Add(entity);
			await _db.SaveChangesAsync();
		}

		public async Task<TemplateDataType> UpdateAsync(TemplateDataType entity)
		{
			var result = _db.TemplateDataTypes.Update(entity);
			return await _db.SaveChangesAsync() > 0 ? result.Entity : new TemplateDataType();
		}

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.TemplateDataTypes.FindAsync(id);
			if (entity != null)
			{
				_db.TemplateDataTypes.Remove(entity);
				await _db.SaveChangesAsync();
			}
        }
	}
}
