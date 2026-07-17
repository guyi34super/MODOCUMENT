using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.DTOs;
using MoDocument.Application.UseCases;

namespace ValidationEngine.WebApi.Controllers
{
    [ApiController]
    [Route("api/[controller]")]
    public class ValidationController : ControllerBase
    {
        private readonly IValidateRowsUseCase _useCase;
        public ValidationController(IValidateRowsUseCase useCase) => _useCase = useCase;

        [HttpPost]
        [Route("/Validation/validate")]
        public async Task<IActionResult> Validate([FromBody] ValidationRequest request)
        {
            var results = await _useCase.HandleAsync(request);
            return Ok(results);
        }
    }
}
