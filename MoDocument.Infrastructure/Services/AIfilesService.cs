using Microsoft.Extensions.Configuration;
using Microsoft.SemanticKernel;
using Microsoft.SemanticKernel.ChatCompletion;
using MoDocument.Application.Interfaces;

namespace MoDocument.Infrastructure.Services
{
	public class AIfilesService : IAIfilesService
	{
		private readonly IChatCompletionService _chatCompletion;

		public AIfilesService(IConfiguration configuration)
		{
			var apiKey = configuration["AIfiles:OpenAI:ApiKey"];
			if (string.IsNullOrWhiteSpace(apiKey))
				throw new InvalidOperationException("AIfiles:OpenAI:ApiKey must be configured in appsettings.");

			var modelId = configuration["AIfiles:OpenAI:ModelId"] ?? "gpt-4o";

			var builder = Kernel.CreateBuilder();
			builder.AddOpenAIChatCompletion(modelId, apiKey);
			var kernel = builder.Build();
			_chatCompletion = kernel.GetRequiredService<IChatCompletionService>();
		}

		public async Task<string> SendPromptAsync(string prompt)
		{
			if (string.IsNullOrWhiteSpace(prompt))
				throw new ArgumentException("Prompt cannot be empty.", nameof(prompt));

			var response = await _chatCompletion.GetChatMessageContentAsync(prompt);
			return response.Content ?? string.Empty;
		}
	}
}
