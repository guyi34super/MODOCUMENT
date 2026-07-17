using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Application.Interfaces
{
	/// <summary>
	/// Knows how to build a prompt string for the LLM from raw text (Excel/CSV/PDF/OCR).
	/// </summary>
	public interface IPromptBuilder
	{
		/// <summary>
		/// Given the entire file content (plain text), returns the full prompt
		/// (including instructions, examples, and the “```excelfile … ```” wrapper).
		/// </summary>
		string BuildPrompt(string fileContent);
	}
}
