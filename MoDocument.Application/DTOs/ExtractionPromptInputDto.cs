using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.DTOs
{
	public class ExtractionPromptInputDto : PromptInputDto
	{
		public string fileContent { get; set; } = string.Empty;
		public string model { get; set; } = string.Empty;
	}
}
