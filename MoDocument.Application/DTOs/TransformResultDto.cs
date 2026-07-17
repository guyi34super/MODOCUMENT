using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.DTOs
{
	/// <summary>
	/// Represents the result of the transformation.
	/// In this case, it’s the JSON string extracted by the LLM.
	/// </summary>
	public class TransformResultDto
	{
		public string JsonPayload { get; set; } = string.Empty;
	}
}
