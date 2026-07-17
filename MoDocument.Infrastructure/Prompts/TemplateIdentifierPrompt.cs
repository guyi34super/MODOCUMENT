using ClosedXML.Excel;
using CsvHelper.Configuration;
using CsvHelper;
using System.Text;
using OpenAI;
using UglyToad.PdfPig;
using UglyToad.PdfPig.Content;
using MoDocument.Application.DTOs;
public class TemplateIdentifierPrompt : IPromptStrategy
{
	public string Type => "TemplateIdentifier";
	public string BuildPrompt(PromptInputDto input)
	{

		var templateIdentifier = input as TemplateIdentifierPromptInputDto;
		if (templateIdentifier == null)
			throw new ArgumentException("Invalid input type for AutoMappingPrompt");

		string employeeDetailsTemplate = "EmployeeDetails: contains columns like Name, LastName, FirstName, DateOfBirth, Address, HireDate";
		string YTDTemplate = "YTD: contains year-to-date journal entries, ranges of dates with values";
		string timeSheetTemplate = "Timesheet: contains earnings codes and hours worked per pay period";

		// 2. Pull a small snippet from the file
		string snippet = ExtractSnippet(templateIdentifier.filePath, maxChars: 2000);

		// 3. Build your classifier prompt
		var prompt = new StringBuilder();
		prompt.AppendLine("You are a document-template classifier.");
		prompt.AppendLine("Choose exactly one of the following templates based on the file snippet:");
		prompt.AppendLine($"  • {employeeDetailsTemplate}");
		prompt.AppendLine($"  • {YTDTemplate}");
		prompt.AppendLine($"  • {timeSheetTemplate}");
		prompt.AppendLine();
		prompt.AppendLine("File snippet:");
		prompt.AppendLine("```");
		prompt.AppendLine(snippet);
		prompt.AppendLine("```");
		prompt.AppendLine();
		prompt.Append("Answer with exactly one mapped integer template (EmployeeDetails as 1, YTD as 4 as , or Timesheet as 2).");

		return prompt.ToString();
	}

	string ExtractSnippet(string path, int maxChars)
	{
		var ext = Path.GetExtension(path).ToLowerInvariant();
		string text;
		switch (ext)
		{
			case ".xlsx":
			case ".xls":
				text = ExtractFromExcel(path);
				break;
			case ".csv":
				text = ExtractFromCsv(path);
				break;
			case ".pdf":
				text = ExtractFromPdf(path);
				break;
			default:
				throw new NotSupportedException($"Unsupported file type: {ext}");
		}
		// truncate to avoid huge prompts
		return text.Length <= maxChars ? text : text.Substring(0, maxChars);
	}

	string ExtractFromExcel(string path)
	{
		var sb = new StringBuilder();
		using var wb = new XLWorkbook(path);
		var ws = wb.Worksheets.First();
		foreach (var row in ws.RowsUsed().Take(5))
		{
			foreach (var cell in row.CellsUsed().Take(10))
				sb.Append(cell.GetValue<string>()).Append('\t');
			sb.AppendLine();
		}
		return sb.ToString();
	}

	string ExtractFromCsv(string path)
	{
		using var reader = new StreamReader(path);
		using var csv = new CsvReader(reader, new CsvConfiguration(System.Globalization.CultureInfo.InvariantCulture));
		var sb = new StringBuilder();
		// read header + first 5 records
		csv.Read();
		csv.ReadHeader();
		sb.AppendLine(string.Join('\t', csv.HeaderRecord));
		int count = 0;
		while (csv.Read() && count++ < 5)
			sb.AppendLine(string.Join('\t', csv.Context.Reader.Read()));
		return sb.ToString();
	}

	string ExtractFromPdf(string path)
	{
		var sb = new StringBuilder();
		using var doc = PdfDocument.Open(path);
		foreach (Page page in doc.GetPages().Take(2))
			sb.AppendLine(page.Text);
		return sb.ToString();
	}
}
