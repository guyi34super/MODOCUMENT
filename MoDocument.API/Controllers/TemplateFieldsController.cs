using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class TemplateFieldsController : ControllerBase
	{
		private readonly ITemplateFieldsRepository _repo;
		public TemplateFieldsController(ITemplateFieldsRepository repo) => _repo = repo;

		// GET api/templateFields
		[HttpGet]
        [Route("/TemplateFields/GetAll")]
        [SwaggerOperation("getAllTemplateFields")]
        public async Task<IActionResult> GetAll()
			=> Ok(await _repo.ListAsync());

		// GET api/templateFields/{id}
		[HttpGet("{id:int}")]
        [Route("/TemplateFields/GetById/{Id}")]
        [SwaggerOperation("getTemplateFieldById")]
        public async Task<IActionResult> GetTemplateFieldsById(int id)
		{
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new TemplateFields
                {
                    TemplateFieldsId = item.TemplateFieldsId,
                    TemplateId = item.TemplateId,
                    FieldName = item.FieldName,
                    Template = item.Template,
                    TemplateDataTypeId = item.TemplateDataTypeId
                };
                return Ok(dto);
            }
        }

		// POST api/templateFields
		[HttpPost]
        [Route("/TemplateFields/Create")]
        [SwaggerOperation("postTemplateField")]
        public async Task<IActionResult> Create(TemplateFields dto)
		{
			var entity = new TemplateFields { TemplateId = dto.TemplateId, FieldName = dto.FieldName };
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetTemplateFieldsById), new { id = entity.TemplateFieldsId }, entity);
		}

        // PUT api/templateFields/{id}
        [HttpPut("{id:int}")]
        [Route("/TemplateFields/Update/{id}")]
        [SwaggerOperation("updateTemplateField")]
        public async Task<IActionResult> Update(TemplateFields dto)
		{
			var existing = await _repo.GetByIdAsync(dto.TemplateFieldsId);
			if (existing is null) return NotFound();

			existing.FieldName = dto.FieldName;
            existing.TemplateId = dto.TemplateId;

            var response = await _repo.UpdateAsync(existing);

            var updated = new TemplateFields
            {
                TemplateFieldsId = response.TemplateFieldsId,
                TemplateId = response.TemplateId,
                FieldName = response.FieldName,
                Template = response.Template,
                TemplateDataTypeId = response.TemplateDataTypeId
            };

            return CreatedAtAction(nameof(GetTemplateFieldsById), new { id = updated.TemplateFieldsId }, updated);
        }

        // DELETE api/templateFields/{id}
        [HttpDelete("{id:int}")]
        [Route("/TemplateFields/{id}")]
        [SwaggerOperation("deleteTemplateField")]
        public async Task<IActionResult> Delete(int id)
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
