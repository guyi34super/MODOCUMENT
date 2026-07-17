namespace MoDocument.Application.Interfaces
{
	/// <summary>
	/// Sends prompts to the LLM via Semantic Kernel / OpenAI and returns the raw reply.
	/// </summary>
	public interface IAIfilesService
	{
		Task<string> SendPromptAsync(string prompt);
	}
}
