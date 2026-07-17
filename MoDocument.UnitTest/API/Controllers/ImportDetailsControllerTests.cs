using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class ImportDetailsControllerTests
	{
		private readonly Mock<IImportDetailsRepository> _mockRepo;
		private readonly ImportDetailsController _controller;

		public ImportDetailsControllerTests()
		{
			_mockRepo = new Mock<IImportDetailsRepository>();
			_controller = new ImportDetailsController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfImportDetailsTypes()
		{
			// Arrange
			var ImportDetails = new List<ImportDetails>
			{
				new ImportDetails { ImportDetailsId = 1, ImportHistoryId = 1, ProcessStepId = 1, RawJson = "{TestJson1}" },
				new ImportDetails { ImportDetailsId = 2, ImportHistoryId = 2, ProcessStepId = 2, RawJson = "{TestJson2}" }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(ImportDetails);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<ImportDetails>>(okResult.Value);
			Assert.Equal(2, ((List<ImportDetails>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ImportDetails)null);

			// Act
			var result = await _controller.GetImportDetailsById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithImportDetails()
		{
			// Arrange
			var ImportDetails = new ImportDetails { ImportDetailsId = 1, ImportHistoryId = 1, ProcessStepId = 1, RawJson = "{TestJson1}" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(ImportDetails);

			// Act
			var result = await _controller.GetImportDetailsById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<ImportDetails>(okResult.Value);
			Assert.Equal(ImportDetails.ImportDetailsId, returnValue.ImportDetailsId);
			Assert.Equal(ImportDetails.ImportHistoryId, returnValue.ImportHistoryId);
            Assert.Equal(ImportDetails.ProcessStepId, returnValue.ProcessStepId);
            Assert.Equal(ImportDetails.RawJson, returnValue.RawJson);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewImportDetails()
		{
			// Arrange
			var dto = new ImportDetails { ImportHistoryId = 1,
										  ProcessStepId = 1, 
										  RawJson = "{TestJson}"
										};
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<ImportDetails>()));

			// Act
			var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<ImportDetails>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal(1, returnValue.ImportHistoryId);
            Assert.Equal(1, returnValue.ProcessStepId);
            Assert.Equal("{TestJson}", returnValue.RawJson);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new ImportDetails { ImportDetailsId = 2, CeridianCompany = "TestCeridianCompany", ProcessStepId = 1, RawJSON = "{TestImportDetailsJSON}" };

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenImportDetailsTypeDoesNotExist()
		{
			// Arrange
			var dto = new ImportDetails { ImportDetailsId = 1, ImportHistoryId = 1, ProcessStepId = 1, RawJson = "{TestJson1}" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ImportDetails)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new ImportDetails { ImportDetailsId = 1, ImportHistoryId = 1, ProcessStepId = 1, RawJson = "{TestJson1}" };
			var dto = new ImportDetails { ImportDetailsId = 1, ImportHistoryId = 2, ProcessStepId = 2, RawJson = "{TestJson2}" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<ImportDetails>()))
                     .ReturnsAsync((ImportDetails v) => v);

            // Act
            var result = await _controller.Update(dto);

            // Assert
            Assert.Equal(2, existing.ImportHistoryId);
            Assert.Equal(2, existing.ProcessStepId);
			Assert.Equal("{TestJson2}", existing.RawJson);
        }

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
			// Arrange
			var existing = new ImportDetails { ImportDetailsId = 1 };
            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

            // Act
            var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
