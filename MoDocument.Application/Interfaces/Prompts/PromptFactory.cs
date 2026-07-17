using Microsoft.Extensions.DependencyInjection;

public class PromptFactory
{
	private readonly IEnumerable<IPromptStrategy> _strategies;

	public PromptFactory(IEnumerable<IPromptStrategy> strategies)
	{
		_strategies = strategies;
	}

	public IPromptStrategy GetPrompt(string type)
	{
		var strategy = _strategies.FirstOrDefault(s =>
			(s as dynamic).Type == type);

		if (strategy == null)
			throw new ArgumentException("Unknown prompt type");

		return strategy;
	}
}
