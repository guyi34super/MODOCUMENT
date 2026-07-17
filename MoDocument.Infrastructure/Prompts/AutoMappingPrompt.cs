using Microsoft.AspNetCore.Connections.Features;
using MoDocument.Application.DTOs;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Infrastructure.Prompts
{
	public class AutoMappingPrompt : IPromptStrategy
	{
		public string Type => "AutoMapping";
		public string BuildPrompt(PromptInputDto input)
		{
			var autoMapperInput = input as AutoMappingPromptInputDto;
			if (autoMapperInput == null)
				throw new ArgumentException("Invalid input type for AutoMappingPrompt");

			// 3. Build your classifier prompt
			var prompt = new StringBuilder();
			prompt.AppendLine("You are an Auto mapper classifier.");
			prompt.AppendLine("I have these CSV columns:");
			prompt.AppendLine(autoMapperInput.ExtractedFields);
			prompt.AppendLine("And these schema fields:");
			prompt.AppendLine(autoMapperInput.SchemaFields);
			prompt.AppendLine("Please return a JSON object mapping each CSV column to the best matching schema field, or null if no good match");				
			return prompt.ToString();
		}
	}
	
}
