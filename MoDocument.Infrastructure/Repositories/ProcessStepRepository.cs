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
	public class ProcessStepRepository : IProcessStepRepository
    {
		private readonly AppDbContext _db;
		public ProcessStepRepository(AppDbContext db) => _db = db;

		public async Task<ProcessStep?> GetByIdAsync(int id) =>
			await _db.ProcessStep.FindAsync(id);

		public async Task<List<ProcessStep>> ListAsync() =>
			await _db.ProcessStep
					.AsNoTracking()
					 .ToListAsync();

		public async Task AddAsync(ProcessStep entity)
		{
			_db.ProcessStep.Add(entity);
			await _db.SaveChangesAsync();
		}

		public async Task<ProcessStep> UpdateAsync(ProcessStep entity)
		{
            var result = _db.ProcessStep.Update(entity);
            return await _db.SaveChangesAsync() > 0 ? result.Entity : new ProcessStep();
        }

		public async Task DeleteAsync(int id)
		{
			var entity = await _db.ProcessStep.FindAsync(id);
			if (entity != null)
			{
				_db.ProcessStep.Remove(entity);
				await _db.SaveChangesAsync();
			}
		}
	}
}
