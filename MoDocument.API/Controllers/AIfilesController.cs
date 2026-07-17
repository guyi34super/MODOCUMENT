using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class AIfilesController : ControllerBase
	{
		private readonly IAIfilesService _aifilesService;

		public AIfilesController(IAIfilesService aifilesService)
		{
			_aifilesService = aifilesService
				?? throw new ArgumentNullException(nameof(aifilesService));
		}

		/// <summary>
		/// POST /api/aifiles/send
		/// Body (JSON): { "prompt": "Your prompt here..." }
		/// </summary>
		[HttpPost("send")]
		public async Task<IActionResult> SendPrompt([FromBody] PromptRequest body)
		{
			if (body == null || string.IsNullOrWhiteSpace(body.Prompt))
				return BadRequest("Prompt must not be empty.");

			try
			{
				var aiResponse = await _aifilesService.SendPromptAsync(body.Prompt);
				return Ok(new { response = aiResponse });
			}
			catch (Exception ex)
			{
				return StatusCode(500, $"Error calling LLM: {ex.Message}");
			}
		}

		public class PromptRequest
		{
			public string Prompt { get; set; } = string.Empty;
		}
	}
}
