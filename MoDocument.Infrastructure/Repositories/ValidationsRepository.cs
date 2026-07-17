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
	public class ValidationsRepository : IValidationsRepository
    {
		private readonly AppDbContext _db;
		public ValidationsRepository(AppDbContext db) => _db = db;

		public async Task<List<Validations>> ListAsync() =>
			await _db.Validations
					.AsNoTracking()
					 .ToListAsync();

		public async Task<Validations?> GetByIdAsync(int id) =>
			await _db.Validations.FindAsync(id);

		public async Task<Validations> AddAsync(Validations entity)
		{
            var templateField = await _db.TemplateFields.FirstOrDefaultAsync(x => x.TemplateFieldsId.Equals(entity.TemplateFieldsId));

            if (templateField == null)
                return new Validations();

            entity.TemplateFields = templateField;
            var result = await _db.Validations.AddAsync(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new Validations();
        }

		public async Task<Validations> UpdateAsync(Validations entity)
		{
            var templateField = await _db.TemplateFields.FirstOrDefaultAsync(x => x.TemplateFieldsId.Equals(entity.TemplateFieldsId));

            if (templateField == null)
                return new Validations();

            entity.TemplateFields = templateField;
            var result = _db.Validations.Update(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new Validations();
        }

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.Validations.FindAsync(id);
			if (entity != null)
			{
				_db.Validations.Remove(entity);
				await _db.SaveChangesAsync();
			}
		}
	}
}
