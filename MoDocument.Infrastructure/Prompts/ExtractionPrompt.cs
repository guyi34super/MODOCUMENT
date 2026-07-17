using MoDocument.Application.DTOs;
using MoDocument.Domain.Entities;
using System.Text;
namespace MoDocument.Infrastructure.Prompts
{
	public class ExtractionPrompt : IPromptStrategy
	{
		public string Type => "Extraction"; 

		public string BuildPrompt(PromptInputDto input)
		{
			var extractionInput = input as ExtractionPromptInputDto;
			if (extractionInput == null)
				throw new ArgumentException("Invalid input type for AutoMappingPrompt");

			var sb = new StringBuilder();
			sb.AppendLine("You are a Data-Extraction Assistant.");
			sb.AppendLine("You will receive a raw text snippet containing field names and values in any format (CSV, PDF-text, key-value lines, etc.).");
			sb.AppendLine();
			sb.AppendLine("Your job is to:");
			sb.AppendLine("1. Identify every field name and its associated value in the snippet.");
			sb.AppendLine("2. Normalize:");
			sb.AppendLine("   • Dates → ISO 8601 (YYYY-MM-DD)");
			sb.AppendLine("   • Numbers → plain numeric strings (no symbols or commas)");
			sb.AppendLine("3. Discard any fields not in the canonical list.");
			sb.AppendLine("4. **Output only** a single JSON object (no markdown, no commentary) where each property is the canonical name and its value is the extracted data (string, number, or null).");
			sb.AppendLine();
			sb.AppendLine("Here is a model that you need to use to extract the data based upon:");
			sb.AppendLine(extractionInput.model);
			sb.AppendLine("**Example**");
			sb.AppendLine("Input snippet:");
			sb.AppendLine("Employee Name: Stephen");
			sb.AppendLine("Middle Name: Lyn Nicole");
			sb.AppendLine("Last Name: Curie");
			sb.AppendLine("Birth Date: 11-07-2002");
			sb.AppendLine("Address: 8775 McNab Cres, Regina, SK S4S 4A9");
			sb.AppendLine("Hourly Rate: $30.50");
			sb.AppendLine("Vacation Annual Hours: 120");
			sb.AppendLine();
			sb.AppendLine("Expected output: JSON such as:");
			sb.AppendLine("```json");
			sb.AppendLine("[");
			sb.AppendLine("  {");
			sb.AppendLine("    \"firstName\": \"Stephen\",");
			sb.AppendLine("    \"middleName\": \"Lyn Nicole\",");
			sb.AppendLine("    \"lastName\": \"Curie\",");
			sb.AppendLine("    \"birthDate\": \"2002-07-11\",");
			sb.AppendLine("    \"address\": \"8775 McNab Cres, Regina, SK S4S 4A9\",");
			sb.AppendLine("    \"hourlyRate\": \"30.50\",");
			sb.AppendLine("    \"gross pay\": \"120\",");
			sb.AppendLine("    \"EL wages\": \"120\"");
			sb.AppendLine("  }");
			sb.AppendLine("]");
			sb.AppendLine("```");
			sb.AppendLine();
			sb.AppendLine("I’ve attached an Excel/CSV/PDF content below:");
			sb.AppendLine("```excelfile");
			sb.AppendLine(extractionInput.fileContent);
			sb.AppendLine("```");

			return sb.ToString();
		}
	}
}

