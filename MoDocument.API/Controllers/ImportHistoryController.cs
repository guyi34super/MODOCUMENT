using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using Swashbuckle.AspNetCore.Annotations;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class ImportHistoryController : ControllerBase
	{
		private readonly IImportHistoryRepository _repo;
		public ImportHistoryController(IImportHistoryRepository repo) =>
			_repo = repo;

		// GET api/importHistory
		[HttpGet]
        [Route("/ImportHistory/GetAll")]
        [SwaggerOperation("getAllImportHistory")]
        public async Task<IActionResult> GetAll()
			=> Ok(await _repo.ListAsync());

		// GET api/importHistory/{id}
		[HttpGet("{id:int}")]
        [Route("/ImportHistory/GetById/{Id}")]
        [SwaggerOperation("getImportHistoryById")]
        public async Task<IActionResult> GetImportHistoryById(int id)
		{
            var item = await _repo.GetByIdAsync(id);
            if (item is null)
            {
                return NotFound();
            }
            else
            {
                var dto = new ImportHistory
                {
                    ImportHistoryId = item.ImportHistoryId,
                    ImportDate = item.ImportDate,
                    FileName = item.FileName,
                    CompanyId = item.CompanyId,
                    TemplateId = item.TemplateId,
                    Template = item.Template
                };

                return Ok(dto);
            }
        }

		// POST api/importHistory
		[HttpPost]
        [Route("/ImportHistory/Create")]
        [SwaggerOperation("postImportHistory")]
        public async Task<IActionResult> Create(ImportHistory dto)
		{
			var entity = new ImportHistory {ImportDate = dto.ImportDate, 
											FileName = dto.FileName, 
											CompanyId = dto.CompanyId, 
											TemplateId = dto.TemplateId,
                                            Template = dto.Template
            };
			await _repo.AddAsync(entity);
			return CreatedAtAction(nameof(GetImportHistoryById), new { id = entity.ImportHistoryId }, entity);
		}

        // PUT api/importHistory/{id}
        [HttpPut("{id:int}")]
        [Route("/ImportHistory/Update/{id}")]
        [SwaggerOperation("updateImportHistory")]
        public async Task<IActionResult> Update(ImportHistory dto)
		{
			var existing = await _repo.GetByIdAsync(dto.ImportHistoryId);
			if (existing is null) return NotFound();

            existing.ImportDate = dto.ImportDate;
            existing.FileName = dto.FileName;
            existing.CompanyId = dto.CompanyId;
            existing.TemplateId = dto.TemplateId;
            existing.Template = dto.Template;

            var response = await _repo.UpdateAsync(existing);

            var updated = new ImportHistory
            {  
                ImportHistoryId = response.ImportHistoryId,
                ImportDate = response.ImportDate,
                FileName = response.FileName,
                CompanyId = response.CompanyId,
                TemplateId = response.TemplateId,
                Template = response.Template,
            };

            return CreatedAtAction(nameof(GetImportHistoryById), new { id = updated.ImportHistoryId }, updated);
        }

        // DELETE api/importHistory/{id}
        [HttpDelete("{id:int}")]
        [Route("/ImportHistory/{id}")]
        [SwaggerOperation("deleteImportHistory")]
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
