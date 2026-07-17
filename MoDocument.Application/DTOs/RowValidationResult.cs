using System.Collections.Generic;
using MoDocument.Domain.Entities;

namespace MoDocument.Application.DTOs
{
    public record RowValidationResult(int Row, string FieldName, string RawValue, string? ErrorMessage = null);
}
