using Microsoft.AspNetCore.Mvc;

namespace MoDocument.API.Controllers
{
	[ApiController]
	[Route("api/[controller]")]
	public class FileUploadController : Controller
	{
		private readonly IWebHostEnvironment _environment;

		public FileUploadController(IWebHostEnvironment environment)
		{
			_environment = environment;
		}

		[HttpPost("upload")]
		[Consumes("multipart/form-data")]
		public async Task<IActionResult> UploadFile(IFormFile file)
		{
			if (file == null || file.Length == 0)
				return BadRequest("No file uploaded.");

			var uploadsFolder = Path.Combine(_environment.WebRootPath ?? Path.Combine(Directory.GetCurrentDirectory(), "wwwroot"), "uploads");
			Directory.CreateDirectory(uploadsFolder);

			//var filePath = Path.Combine(uploadsFolder, Path.GetFileName(file.FileName));
			var fileNameWithDateTime = $"{Path.GetFileNameWithoutExtension(file.FileName)}_{DateTime.Now:yyyyMMddHHmmss}{Path.GetExtension(file.FileName)}";
			var filePath = Path.Combine(uploadsFolder, fileNameWithDateTime);
			using (var stream = new FileStream(filePath, FileMode.Create))
			{
				await file.CopyToAsync(stream);
			}


			return Ok(new { FileName = fileNameWithDateTime, Name = fileNameWithDateTime, file.Length, filePath });
		}
	}
}
