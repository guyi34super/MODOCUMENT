using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.DTOs;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.Infrastructure.Prompts;
using MoDocument.Infrastructure.Prompts;
using MoDocument.Infrastructure.Repositories;
using MoDocument.Infrastructure.Services;
using System.Text.Json;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class AutoMapperController : ControllerBase
	{
		private readonly IPromptStrategy _autoMappingPrompt;
		private readonly IImportHistoryRepository _importHistoryRepository;
		private readonly ITemplateFieldsRepository _templateFieldsRepository;
		private readonly PromptFactory _promptFactory;
		private readonly IAIfilesService _aifilesService;
		public AutoMapperController(IEnumerable<IPromptStrategy> strategies, 
			IImportHistoryRepository importHistoryRepository,
			ITemplateFieldsRepository templateFieldsRepository,
			PromptFactory promptFactory,
			IAIfilesService chatService
			)
		{
			// Find the AutoMappingPrompt implementation by type or property
			_autoMappingPrompt = strategies.First(s => s.Type == "AutoMapping");
			_importHistoryRepository = importHistoryRepository;
			_templateFieldsRepository = templateFieldsRepository;
			_promptFactory = promptFactory;
			_aifilesService = chatService;
		}

		[HttpPost("generate")]
		public async Task<IActionResult> AutoMap([FromQuery] string extractedFields, [FromQuery] string filename)
		{
			if (string.IsNullOrWhiteSpace(extractedFields) || string.IsNullOrWhiteSpace(filename))
				return BadRequest("Both extractedFields and filename are required.");

			ImportHistory importHistory = await _importHistoryRepository.GetByFileNameAsync(filename);

			var templateFields = (await _templateFieldsRepository.ListAsync())
								.Where(tf => tf.TemplateId == importHistory.TemplateId)
								.ToList();

			var fieldNames = templateFields.Select(tf => tf.FieldName).ToList();

			string schemaFileds = JsonSerializer.Serialize(fieldNames);

			var input = new AutoMappingPromptInputDto
			{
				SchemaFields = schemaFileds,
				ExtractedFields = extractedFields
			};

			var promptStrategy = _promptFactory.GetPrompt("AutoMapping");

			IPromptStrategy strategy = new AutoMappingPrompt();

			string prompt = strategy.BuildPrompt(input);

			// 4. Send the prompt to the LLM via IAIfilesService
			var llmResponse = await _aifilesService.SendPromptAsync(prompt);

			return Ok(new { Prompt = llmResponse });
		}
	}
}
