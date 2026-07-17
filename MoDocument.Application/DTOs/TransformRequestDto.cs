using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.DTOs
{
	/// <summary>
	/// Represents the incoming request from the API layer.
	/// Contains just the file name (e.g. "sample.pdf").
	/// </summary>
	public class TransformRequestDto
	{
		public string FileName { get; set; } = string.Empty;
	}
}
