using Azure;
using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class ProcessStepController : ControllerBase
	{
		private readonly IProcessStepRepository _repo;
		public ProcessStepController(IProcessStepRepository repo) =>
			_repo = repo;

		// GET api/processSteps
		[HttpGet]
        [Route("/ProcessStep/GetAll")]
        [SwaggerOperation("getAllProcessSteps")]
        public async Task<IActionResult> GetAll()
			=> Ok(await _repo.ListAsync());

		// GET api/processSteps/{id}
		[HttpGet("{id:int}")]
        [Route("/ProcessStep/GetById/{Id}")]
        [SwaggerOperation("getProcessStepById")]
        public async Task<IActionResult> GetProcessStepById(int id)
        {
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new ProcessStep
                {
                    ProcessStepId = item.ProcessStepId,
                    Name = item.Name,
                    Description = item.Description
                };
                return Ok(dto);
            }
        }

        // POST api/processSteps
        [HttpPost]
        [Route("/ProcessStep/Create")]
        [SwaggerOperation("postProcessStep")]
        public async Task<IActionResult> Create(ProcessStep dto)
		{
			var entity = new ProcessStep { Name = dto.Name, Description = dto.Description };
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetProcessStepById), new { id = entity.ProcessStepId }, entity);
		}

		// PUT api/processSteps/5
		[HttpPut("{id:int}")]
        [Route("/ProcessStep/Update")]
        [SwaggerOperation("updateProcessStep")]
        public async Task<IActionResult> Update(ProcessStep dto)
        {
            var existing = await _repo.GetByIdAsync(dto.ProcessStepId);
            if (existing is null) return NotFound();

            existing.Name = dto.Name;

            existing.Description = dto.Description;

            var response = await _repo.UpdateAsync(existing);

            var updated = new ProcessStep
            {
                ProcessStepId = response.ProcessStepId,
                Name = response.Name,
                Description = response.Description
            };

            return CreatedAtAction(nameof(GetProcessStepById), new { id = updated.ProcessStepId }, updated);
        }

        // DELETE api/processSteps/5
        [HttpDelete("{id:int}")]
        [Route("/ProcessStep/{id}")]
        [SwaggerOperation("deleteProcessStep")]
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
