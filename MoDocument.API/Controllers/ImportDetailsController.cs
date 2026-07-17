using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
    public class ImportDetailsController : ControllerBase
    {
        private readonly IImportDetailsRepository _repo;
        public ImportDetailsController(IImportDetailsRepository repo) =>
            _repo = repo;

        // GET api/importDetails
        [HttpGet]
        [Route("/ImportDetails/GetAll")]
        [SwaggerOperation("getAllImportDetails")]
        public async Task<IActionResult> GetAll()
            => Ok(await _repo.ListAsync());

        // GET api/importDetails/{id}
        [HttpGet("{id:int}")]
        [Route("/ImportDetails/GetById/{Id}")]
        [SwaggerOperation("getImportDetailsById")]
        public async Task<IActionResult> GetImportDetailsById(int id)
        {
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new ImportDetails
                {
                    ImportDetailsId = item.ImportDetailsId,
                    ImportHistoryId = item.ImportHistoryId,
                    ProcessStepId = item.ProcessStepId,
                    RawJson = item.RawJson,
                    ImportHistory = item.ImportHistory,
                    ProcessStep = item.ProcessStep
                };

                return Ok(dto);
            }
        }

        // POST api/importDetails
        [HttpPost]
        [Route("/ImportDetails/Create")]
        [SwaggerOperation("postImportDetails")]
        public async Task<IActionResult> Create(ImportDetails dto)
        {
            var entity = new ImportDetails
            {
                ImportHistoryId = dto.ImportHistoryId,
                ProcessStepId   = dto.ProcessStepId,
                RawJson         = dto.RawJson,
                ImportHistory   = dto.ImportHistory,
                ProcessStep     = dto.ProcessStep
            };
            await _repo.AddAsync(entity);
            return CreatedAtAction(nameof(GetImportDetailsById), new { id = entity.ImportDetailsId }, entity);
        }

        // PUT api/importDetails/{id}
        [HttpPut("{id:int}")]
        [Route("/ImportDetails/Update/{id}")]
        [SwaggerOperation("updateImportDetails")]
        public async Task<IActionResult> Update(ImportDetails dto)
        {
            var existing = await _repo.GetByIdAsync(dto.ImportDetailsId);
            if (existing is null) return NotFound();

                existing.ImportHistoryId = dto.ImportHistoryId;
                existing.ProcessStepId   = dto.ProcessStepId;
                existing.RawJson         = dto.RawJson;
                existing.ImportHistory   = dto.ImportHistory;
                existing.ProcessStep     = dto.ProcessStep;

            var response = await _repo.UpdateAsync(existing);

            var updated = new ImportDetails
            {
                ImportDetailsId = response.ImportDetailsId,
                ImportHistoryId = response.ImportHistoryId,
                ProcessStepId   = response.ProcessStepId,
                RawJson         = response.RawJson,
                ImportHistory   = response.ImportHistory,
                ProcessStep     = response.ProcessStep
            };

            return CreatedAtAction(nameof(GetImportDetailsById), new { id = updated.ImportDetailsId }, updated);
        }

        // DELETE api/importDetails/{id}
        [HttpDelete("{id:int}")]
        [Route("/ImportDetails/{id}")]
        [SwaggerOperation("deleteImportDetails")]
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
