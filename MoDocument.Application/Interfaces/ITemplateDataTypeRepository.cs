using MoDocument.Domain.Entities;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.Interfaces
{
	public interface ITemplateDataTypeRepository
	{
		Task<TemplateDataType?> GetByIdAsync(int id);
		Task<List<TemplateDataType>> ListAsync();
		Task AddAsync(TemplateDataType entity);
		Task<TemplateDataType> UpdateAsync(TemplateDataType entity);
		Task DeleteAsync(int id);
	}
}
