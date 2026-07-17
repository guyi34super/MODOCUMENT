using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.DTOs
{
	public class TemplateIdentifierPromptInputDto : PromptInputDto
	{
		public string filePath { get; set; } = string.Empty;
	}
}
