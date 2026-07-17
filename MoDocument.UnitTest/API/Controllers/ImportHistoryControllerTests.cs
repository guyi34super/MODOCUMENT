using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class ImportHistoryControllerTests
	{
		private readonly Mock<IImportHistoryRepository> _mockRepo;
		private readonly ImportHistoryController _controller;

		public ImportHistoryControllerTests()
		{
			_mockRepo = new Mock<IImportHistoryRepository>();
			_controller = new ImportHistoryController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfImportHistoryTypes()
		{
			// Arrange
			var ImportHistory = new List<ImportHistory>
			{
				new ImportHistory { ImportHistoryId = 1, CompanyId = 1, TemplateId = 1, ImportDate = DateTime.Now },
				new ImportHistory { ImportHistoryId = 2, CompanyId = 2, TemplateId = 2, ImportDate = DateTime.Now }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(ImportHistory);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<ImportHistory>>(okResult.Value);
			Assert.Equal(2, ((List<ImportHistory>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ImportHistory)null);

			// Act
			var result = await _controller.GetImportHistoryById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithImportHistory()
		{
			// Arrange
			var ImportHistory = new ImportHistory { ImportHistoryId = 1, CompanyId = 1, TemplateId = 1, ImportDate = DateTime.Now };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(ImportHistory);

			// Act
			var result = await _controller.GetImportHistoryById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<ImportHistory>(okResult.Value);
			Assert.Equal(ImportHistory.ImportHistoryId, returnValue.ImportHistoryId);
			Assert.Equal(ImportHistory.CompanyId, returnValue.CompanyId);
            Assert.Equal(ImportHistory.TemplateId, returnValue.TemplateId);
            Assert.Equal(ImportHistory.ImportDate, returnValue.ImportDate);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewImportHistory()
		{
			// Arrange
			var dto = new ImportHistory { CompanyId = 1,
										  TemplateId = 1,
										  ImportDate = DateTime.Now, 
										  Template = new Template { TemplateId = 1, Name = "NewTemplateName", Description = "NewTemplateDescription" }
										};
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<ImportHistory>()));

			// Act
			var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<ImportHistory>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal(1, returnValue.CompanyId);
            Assert.Equal(1, returnValue.TemplateId);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new ImportHistory { ImportHistoryId = 2, CeridianCompany = "TestCeridianCompany", ProcessStepId = 1, RawJSON = "{TestImportHistoryJSON}" };

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenImportHistoryTypeDoesNotExist()
		{
			// Arrange
			var dto = new ImportHistory { ImportHistoryId = 1, CompanyId = 1, TemplateId = 1, ImportDate = DateTime.Now };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ImportHistory)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new ImportHistory { ImportHistoryId = 1, CompanyId = 1, TemplateId = 1, ImportDate = DateTime.Now };
			var dto = new ImportHistory { ImportHistoryId = 1, CompanyId = 2, TemplateId = 2, ImportDate = DateTime.Now };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<ImportHistory>()))
                     .ReturnsAsync((ImportHistory v) => v);

            // Act
            var result = await _controller.Update(dto);

            // Assert
            Assert.Equal(2, existing.CompanyId);
            Assert.Equal(2, existing.TemplateId);
        }

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
			// Arrange
			var existing = new ImportHistory { ImportHistoryId = 1, CompanyId = 1, TemplateId = 1, ImportDate = DateTime.Now };
            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

            // Act
            var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
