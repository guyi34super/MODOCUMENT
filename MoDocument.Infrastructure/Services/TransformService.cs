using MoDocument.Application.DTOs;
using MoDocument.Application.Interfaces;
using Microsoft.AspNetCore.Hosting;
using ClosedXML.Excel;
using iTextSharp.text.pdf;
using iTextSharp.text.pdf.parser;
using Tesseract;
using System.Text;
using MoDocument.Infrastructure.Prompts;
namespace MoDocument.Infrastructure.Services
{
	public class TransformService : ITransformService
	{
		private readonly IWebHostEnvironment _environment;
		private readonly PromptFactory _promptFactory;
		private readonly IAIfilesService _aifilesService;
		
		public TransformService(
			IWebHostEnvironment environment,
			PromptFactory promptFactory,
			IAIfilesService chatService)
		{
			_environment = environment ?? throw new ArgumentNullException(nameof(environment));
			_promptFactory = promptFactory;
			_aifilesService = chatService ?? throw new ArgumentNullException(nameof(chatService));
		}

		public async Task<TransformResultDto> TransformAsync(TransformRequestDto request, string model)
		{
			if (request == null)
				throw new ArgumentNullException(nameof(request));
			if (string.IsNullOrWhiteSpace(request.FileName))
				throw new ArgumentException("FileName must be provided.", nameof(request.FileName));

			// 1. Build physical path under wwwroot/uploads
			var webRoot = _environment.WebRootPath
								?? System.IO.Path.Combine(Directory.GetCurrentDirectory(), "wwwroot");
			var uploadsFolder = System.IO.Path.Combine(webRoot, "uploads");
			var filePath = System.IO.Path.Combine(uploadsFolder, request.FileName);

			if (!File.Exists(filePath))
				throw new FileNotFoundException(
					$"File '{request.FileName}' not found in uploads folder.",
					request.FileName);

			// 2. Read the file (Excel/CSV/PDF+OCR) into plain text
			var fileContent = ReadFileAsText(filePath);

			// 3. Build the prompt via IPromptBuilder
			var promptStrategy = _promptFactory.GetPrompt("Extraction");

			var input = new ExtractionPromptInputDto { fileContent = fileContent,model = model};
			IPromptStrategy strategy = new ExtractionPrompt();
			string prompt = strategy.BuildPrompt(input);

			// 4. Send the prompt to the LLM via IAIfilesService
			var llmResponse = await _aifilesService.SendPromptAsync(prompt);

			// 5. Extract only the JSON between the markers
			var extractedJson = ExtractBetween(llmResponse, "```json", "```");

			return new TransformResultDto { JsonPayload = extractedJson };
		}

		// write a method that return an integer
		public async Task<int> GetTemplateId(TransformRequestDto request)
		{
			if (request == null)
				throw new ArgumentNullException(nameof(request));
			if (string.IsNullOrWhiteSpace(request.FileName))
				throw new ArgumentException("FileName must be provided.", nameof(request.FileName));

			// 1. Build physical path under wwwroot/uploads
			var webRoot = _environment.WebRootPath
								?? System.IO.Path.Combine(Directory.GetCurrentDirectory(), "wwwroot");
			var uploadsFolder = System.IO.Path.Combine(webRoot, "uploads");
			var filePath = System.IO.Path.Combine(uploadsFolder, request.FileName);

			var promptStrategy = _promptFactory.GetPrompt("TemplateIdentifier");

			var input = new TemplateIdentifierPromptInputDto { filePath = filePath };
			// 3. Build the prompt via IPromptBuilder
			IPromptStrategy strategy = new TemplateIdentifierPrompt();

			string prompt = strategy.BuildPrompt(input);

			// 4. Send the prompt to the LLM via IAIfilesService
			var llmResponse = await _aifilesService.SendPromptAsync(prompt);

			var classification = Convert.ToInt32(llmResponse.Trim());

			Console.WriteLine($"LLM classified as: {classification}");
			return classification; // Example implementation, replace with actual logic as needed
		}
		#region File Reading Helpers

		private string ReadFileAsText(string path)
		{
			var sb = new StringBuilder();
			var extension = System.IO.Path.GetExtension(path)?.ToLowerInvariant();

			switch (extension)
			{
				case ".xlsx":
					using (var workbook = new XLWorkbook(path))
					{
						var worksheet = workbook.Worksheet(1);
						foreach (var row in worksheet.RowsUsed())
						{
							foreach (var cell in row.CellsUsed())
							{
								sb.Append(cell.GetValue<string>());
								sb.Append("\t");
							}
							sb.AppendLine();
						}
					}
					break;

				case ".csv":
					using (var reader = new StreamReader(path))
					{
						while (!reader.EndOfStream)
						{
							var line = reader.ReadLine();
							sb.AppendLine(line);
						}
					}
					break;

				case ".pdf":
					sb.Append(ReadPdfFile(path));
					break;

				default:
					throw new NotSupportedException($"File type '{extension}' is not supported.");
			}

			return sb.ToString();
		}

		private string ReadPdfFile(string path)
		{
			var sb = new StringBuilder();
			using (var reader = new PdfReader(path))
			{
				for (int i = 1; i <= reader.NumberOfPages; i++)
				{
					// Attempt plain text extraction
					var pageText = PdfTextExtractor.GetTextFromPage(reader, i)?.Trim();
					if (!string.IsNullOrEmpty(pageText))
					{
						sb.AppendLine(pageText);
					}
					else
					{
						// If no text found, do OCR on images
						var pageDict = reader.GetPageN(i);
						var resources = pageDict.GetAsDict(PdfName.RESOURCES);
						var xObject = resources?.GetAsDict(PdfName.XOBJECT);

						if (xObject != null)
						{
							foreach (var key in xObject.Keys)
							{
								var obj = xObject.GetAsStream(key);
								if (obj is PRStream prStream)
								{
									var pdfImage = new PdfImageObject(prStream);
									using (var bitmap = pdfImage.GetDrawingImage() as System.Drawing.Bitmap)
									{
										if (bitmap != null)
										{
											using (var memStream = new MemoryStream())
											{
												bitmap.Save(memStream, System.Drawing.Imaging.ImageFormat.Bmp);
												memStream.Seek(0, SeekOrigin.Begin);

												using (var pix = Pix.LoadFromMemory(memStream.ToArray()))
												using (var engine = new TesseractEngine(@"tessdata", "eng", EngineMode.Default))
												using (var pageOcr = engine.Process(pix))
												{
													sb.AppendLine(pageOcr.GetText());
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
			return sb.ToString();
		}

		private static string ExtractBetween(string input, string start, string end)
		{
			if (string.IsNullOrEmpty(input) || string.IsNullOrEmpty(start) || string.IsNullOrEmpty(end))
				return string.Empty;

			var startIndex = input.IndexOf(start, StringComparison.Ordinal);
			if (startIndex < 0) return string.Empty;
			startIndex += start.Length;

			var endIndex = input.IndexOf(end, startIndex, StringComparison.Ordinal);
			if (endIndex < 0) return string.Empty;

			return input.Substring(startIndex, endIndex - startIndex).Trim();
		}

		#endregion
	}
}
