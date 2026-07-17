using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using MoDocument.Application.DTOs;
namespace MoDocument.Application.Interfaces
{
	/// <summary>
	/// Defines the “Use‐Case” for transforming a file (PDF/Excel/CSV) into a JSON payload.
	/// </summary>
	public interface ITransformService
	{
		Task<TransformResultDto> TransformAsync(TransformRequestDto request, string model);

		Task<int> GetTemplateId(TransformRequestDto request);
	}
}
