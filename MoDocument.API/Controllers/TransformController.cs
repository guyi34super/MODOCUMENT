using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Application.DTOs;
using MoDocument.Domain.Entities;
using MoDocument.Infrastructure.Repositories;
using System.Text.Json;
namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class TransformController : ControllerBase
	{
		private readonly ITransformService _transformService;
		private readonly IImportHistoryRepository _importHistoryRepository;
		private readonly IImportDetailsRepository _importDetailsRepository;
		private readonly ITemplateFieldsRepository _templateFieldsRepository;
		public TransformController(ITransformService transformService, ITemplateFieldsRepository templateFieldsRepository, IImportHistoryRepository importHistoryRepository, IImportDetailsRepository importDetailsRepository)
		{
			_transformService = transformService
								?? throw new ArgumentNullException(nameof(transformService));
			_templateFieldsRepository = templateFieldsRepository;
			_importHistoryRepository = importHistoryRepository
										?? throw new ArgumentNullException(nameof(importHistoryRepository));
			_importDetailsRepository = importDetailsRepository;
		}

		[HttpPost("transform")]
		public async Task<IActionResult> TransformFile([FromBody] TransformRequestDto request)
		{
			if (request == null || string.IsNullOrWhiteSpace(request.FileName))
				return BadRequest("FileName is required in the request body.");

			try
			{
				// retrieve template by ID
				int templateId = await _transformService.GetTemplateId(request);

				// retrieve template fields by templateId
				List<TemplateFields> templateFields = (await _templateFieldsRepository.ListAsync())
					.Where(tf => tf.TemplateId == templateId)
					.ToList();
				if (templateFields == null || !templateFields.Any())
					return NotFound($"No template fields found for TemplateId: {templateId}");

				List<string> fieldNames = templateFields
							.Select(tf => tf.FieldName?.ToString() ?? string.Empty)
							.ToList();

				string model = JsonSerializer.Serialize(fieldNames);

				// retrieve template by ID
				var resultDto = await _transformService.TransformAsync(request, model);

				ImportHistory import = new ImportHistory { CompanyId = 1000, TemplateId = templateId, FileName = request.FileName, ImportDate = DateTime.UtcNow };
				//Add to import history repository
				await _importHistoryRepository.AddAsync(import);

				//add to import details
				await _importDetailsRepository.AddAsync(new ImportDetails { ImportHistoryId = import.ImportHistoryId, RawJson = resultDto.JsonPayload, ProcessStepId = 1 });


				return Ok(resultDto);
			}
			catch (FileNotFoundException fnfEx)
			{
				return NotFound(fnfEx.Message);
			}
			catch (NotSupportedException nsEx)
			{
				return BadRequest(nsEx.Message);
			}
			catch (Exception ex)
			{
				// In production, log the exception before returning
				return StatusCode(500, $"An unexpected error occurred: {ex.Message}");
			}
		}
	}
}
