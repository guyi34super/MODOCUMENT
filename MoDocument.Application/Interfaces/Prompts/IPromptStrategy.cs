public interface IPromptStrategy
{
	string BuildPrompt(PromptInputDto input);
	string Type { get; }
}
