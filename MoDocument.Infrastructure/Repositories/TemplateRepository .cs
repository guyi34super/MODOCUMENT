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
	public class TemplateRepository : ITemplateRepository
    {
		private readonly AppDbContext _db;
		public TemplateRepository(AppDbContext db) => _db = db;

		public async Task<Template?> GetByIdAsync(int id) =>
			await _db.Template.FindAsync(id);

		public async Task<List<Template>> ListAsync() =>
			await _db.Template
					.AsNoTracking()
					 .ToListAsync();

		public async Task AddAsync(Template entity)
		{
			_db.Template.Add(entity);
			await _db.SaveChangesAsync();
		}

		public async Task<Template> UpdateAsync(Template entity)
		{
            var result = _db.Template.Update(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new Template();
        }

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.Template.FindAsync(id);
			if (entity != null)
			{
				_db.Template.Remove(entity);
				await _db.SaveChangesAsync();
			}
		}
	}
}
