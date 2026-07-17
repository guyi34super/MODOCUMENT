using Azure;
using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class TemplateController : ControllerBase
	{
		private readonly ITemplateRepository _repo;
		public TemplateController(ITemplateRepository repo) =>
			_repo = repo;

		// GET api/templates
		[HttpGet]
        [Route("/Templates/GetAll")]
        [SwaggerOperation("getAllTemplates")]
        public async Task<IActionResult> GetAll()
			=> Ok(await _repo.ListAsync());

		// GET api/templates/5
		[HttpGet("{id:int}")]
        [Route("/Templates/GetById/{Id}")]
        [SwaggerOperation("getTemplateById")]
        public async Task<IActionResult> GetTemplateById(int id)
		{
			var item = await _repo.GetByIdAsync(id);
			if(item is null)
			{
				return NotFound();
			}
			else
			{
				var dto = new Template
				{
					TemplateId = item.TemplateId,
					Name = item.Name,
					Description = item.Description
				};
				return Ok(dto);
			}
		}

		// POST api/templates
		[HttpPost]
        [Route("/Templates/Create")]
        [SwaggerOperation("postTemplate")]
        public async Task<IActionResult> Create(Template dto)
		{
			var entity = new Template { Name = dto.Name, Description = dto.Description};
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetTemplateById), new { id = entity.TemplateId }, entity);
		}

		// PUT api/templates/{id}
		[HttpPut("{id:int}")]
        [Route("/Templates/Update")]
        [SwaggerOperation("updateTemplate")]
        public async Task<IActionResult> Update(Template dto)
		{
			var existing = await _repo.GetByIdAsync(dto.TemplateId);
			if (existing is null) return NotFound();

			existing.Name = dto.Name;

			existing.Description = dto.Description;

            var response = await _repo.UpdateAsync(existing);

            var updated = new Template
            {
                TemplateId = response.TemplateId,
                Name = response.Name,
				Description = response.Description
            };

            return CreatedAtAction(nameof(GetTemplateById), new { id = updated.TemplateId }, updated);
        }

        // DELETE api/templates/{id}
        [HttpDelete("{id:int}")]
        [Route("/Templates/{id}")]
        [SwaggerOperation("deleteTemplate")]
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
