using System.Collections.Generic;
using System.Threading.Tasks;
using MoDocument.Application.DTOs;

namespace MoDocument.Application.UseCases
{
    public interface IValidateRowsUseCase
    {
        Task<List<RowValidationResult>> HandleAsync(ValidationRequest request);
    }
}
