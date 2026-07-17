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
using System.Diagnostics.Metrics;
namespace MoDocument.Infrastructure.Repositories
{
	public class TemplateFieldsRepository : ITemplateFieldsRepository
    {
		private readonly AppDbContext _db;
		public TemplateFieldsRepository(AppDbContext db) => _db = db;

		public async Task<List<TemplateFields>> ListAsync() =>
			await _db.TemplateFields                   
			         .AsNoTracking()
                     .ToListAsync();

        public async Task<TemplateFields?> GetByIdAsync(int id) =>
			await _db.TemplateFields.FindAsync(id);

		public async Task<TemplateFields> AddAsync(TemplateFields entity)
		{
            var template = await _db.Template.FirstOrDefaultAsync(x => x.TemplateId.Equals(entity.TemplateId));

            if (template == null)
                return new TemplateFields();

            entity.Template = template;
            var result = await _db.TemplateFields.AddAsync(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new TemplateFields();
        }

        public async Task<TemplateFields> UpdateAsync(TemplateFields entity)
		{
            var template = await _db.Template.FirstOrDefaultAsync(x => x.TemplateId.Equals(entity.TemplateId));

            if (template == null)
                return new TemplateFields();

            entity.Template = template;
            var result = _db.TemplateFields.Update(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new TemplateFields();
        }

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.TemplateFields.FindAsync(id);
			if (entity != null)
			{
				_db.TemplateFields.Remove(entity);
				await _db.SaveChangesAsync();
			}
		}
	}
}
