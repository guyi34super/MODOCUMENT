namespace MoDocument.Domain.Entities
{
    public record ValidationError(int Row, string FieldName, string RawValue, string ErrorMessage);
}
