using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class TemplateDataTypesController : ControllerBase
	{
		private readonly ITemplateDataTypeRepository _repo;
		public TemplateDataTypesController(ITemplateDataTypeRepository repo) =>
			_repo = repo;

		// GET api/TemplateDataTypes
		[HttpGet]
        [Route("/TemplateDataTypes/GetAll")]
        [SwaggerOperation("getAllTemplateDataTypes")]
        public async Task<IActionResult> GetAllTemplateDataTypes()
			=> Ok(await _repo.ListAsync());

		// GET api/TemplateDataTypes/{id}
		[HttpGet("{id:int}")]
        [Route("/TemplateDataTypes/GetById/{Id}")]
        [SwaggerOperation("getTemplateDataTypeById")]
        public async Task<IActionResult> GetTemplateDataTypeById(int id)
		{
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new TemplateDataType
                {
                    TemplateDataTypeId = item.TemplateDataTypeId,
                    DataType = item.DataType,    
                };
                return Ok(dto);
            }
        }

		// POST api/TemplateDataTypes
		[HttpPost]
        [Route("/TemplateDataTypes/Create")]
        [SwaggerOperation("postTemplateDataType")]
        public async Task<IActionResult> CreateTemplateDataType(TemplateDataType dto)
		{
			var entity = new TemplateDataType { DataType = dto.DataType };
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetTemplateDataTypeById), new { id = entity.TemplateDataTypeId }, entity);
		}

		// PUT api/TemplateDataTypes/{id}
		[HttpPut("{id:int}")]
        [Route("/TemplateDataTypes/Update")]
        [SwaggerOperation("updateTemplateDataType")]
        public async Task<IActionResult> UpdateTemplateDataType(TemplateDataType dto)
		{
            var existing = await _repo.GetByIdAsync(dto.TemplateDataTypeId);
			if (existing is null) return NotFound();

			existing.DataType = dto.DataType;

            var response = await _repo.UpdateAsync(existing);

            var updated = new TemplateDataType
            {
                TemplateDataTypeId = response.TemplateDataTypeId,
                DataType = response.DataType
            };

            return CreatedAtAction(nameof(GetTemplateDataTypeById), new { id = updated.TemplateDataTypeId }, updated);
        }

		// DELETE api/TemplateDataTypes/{id}
		[HttpDelete("{id:int}")]
        [Route("/TemplateDataTypes/{id}")]
        [SwaggerOperation("deleteTemplateDataType")]
        public async Task<IActionResult> DeleteTemplateDataType(int id)
		{
            var existing = await _repo.GetByIdAsync(id);
            if (existing is null) return NotFound();
            else 
            {             
                await _repo.DeleteAsync(id);
			    return NoContent();
            }
		}
	}
}
