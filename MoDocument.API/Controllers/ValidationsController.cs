using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class ValidationsController : ControllerBase
	{
		private readonly IValidationsRepository _repo;
		public ValidationsController(IValidationsRepository repo) =>
			_repo = repo;

        // GET api/Validations
        [HttpGet]
        [Route("/Validations/GetAll")]
        [SwaggerOperation("getAllValidations")]
        public async Task<IActionResult> GetAll()
			=> Ok(await _repo.ListAsync());

        // GET api/Validations/{id}
        [HttpGet("{id:int}")]
        [Route("/Validations/GetById/{Id}")]
        [SwaggerOperation("getValidationById")]
        public async Task<IActionResult> GetValidationById(int id)
        {
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new Validations
                {
                    ValidationId = item.ValidationId,
                    TemplateFieldsId = item.TemplateFieldsId,
                    Name = item.Name,
                    Description = item.Description,
                    TemplateFields = item.TemplateFields
                };
                return Ok(dto);
            }
        }

        // POST api/Validations
        [HttpPost]
        [Route("/Validations/Create")]
        [SwaggerOperation("postValidations")]
        public async Task<IActionResult> Create(Validations dto)
		{
			var entity = new Validations { TemplateFieldsId = dto.TemplateFieldsId, Name = dto.Name, Description = dto.Description };
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetValidationById), new { id = entity.ValidationId }, entity);
		}

        // PUT api/Validations/{id}
        [HttpPut("{id:int}")]
        [Route("/Validations/Update/{id}")]
        [SwaggerOperation("updateValidation")]
        public async Task<IActionResult> Update(Validations dto)
		{
			var existing = await _repo.GetByIdAsync(dto.ValidationId);
			if (existing is null) return NotFound();

			existing.Name = dto.Name;
            existing.TemplateFieldsId = dto.TemplateFieldsId;
            existing.Description = dto.Description;

            var response = await _repo.UpdateAsync(existing);

            var updated = new Validations
            {
                ValidationId = response.ValidationId,
                TemplateFieldsId = response.TemplateFieldsId,
                Name = response.Name,
                Description = response.Description,
                TemplateFields = response.TemplateFields
            };

            return CreatedAtAction(nameof(GetValidationById), new { id = updated.ValidationId }, updated);
        }

        // DELETE api/Validations/{id}
        [HttpDelete("{id:int}")]
        [Route("/Validations/{id}")]
        [SwaggerOperation("deleteValidation")]
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
