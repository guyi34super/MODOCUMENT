using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class TemplateDataTypesControllerTests
	{
		private readonly Mock<ITemplateDataTypeRepository> _mockRepo;
		private readonly TemplateDataTypesController _controller;

		public TemplateDataTypesControllerTests()
		{
			_mockRepo = new Mock<ITemplateDataTypeRepository>();
			_controller = new TemplateDataTypesController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfTemplateDataTypes()
		{
			// Arrange
			var TemplateDataTypes = new List<TemplateDataType>
			{
				new TemplateDataType { TemplateDataTypeId = 1, DataType = "Text" },
				new TemplateDataType { TemplateDataTypeId = 2, DataType = "Number" },
                new TemplateDataType { TemplateDataTypeId = 3, DataType = "Date" }
            };
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(TemplateDataTypes);

			// Act
			var result = await _controller.GetAllTemplateDataTypes();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<TemplateDataType>>(okResult.Value);
			Assert.Equal(3, ((List<TemplateDataType>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((TemplateDataType)null);

			// Act
			var result = await _controller.GetTemplateDataTypeById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithTemplateDataType()
		{
			// Arrange
			var TemplateDataType = new TemplateDataType { TemplateDataTypeId = 1, DataType = "Text" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(TemplateDataType);

			// Act
			var result = await _controller.GetTemplateDataTypeById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<TemplateDataType>(okResult.Value);
			Assert.Equal(TemplateDataType.TemplateDataTypeId, returnValue.TemplateDataTypeId);
			Assert.Equal(TemplateDataType.DataType, returnValue.DataType);
		}

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewTemplateDataType()
		{
			// Arrange
			var dto = new TemplateDataType { DataType = "NewType" };
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<TemplateDataType>()))
					 .Returns(Task.CompletedTask)
					 .Callback<TemplateDataType>(t => t.TemplateDataTypeId = 1);

			// Act
			var result = await _controller.CreateTemplateDataType(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<TemplateDataType>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal("NewType", returnValue.DataType);
		}

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	int id = 1;
		//	var dto = new TemplateDataType { TemplateDataTypeId = 2, DataType = "Test" };

		//	// Act
		//	var result = await _controller.UpdateTemplateDataType(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenTemplateDataTypeDoesNotExist()
		{
			// Arrange
			var dto = new TemplateDataType { TemplateDataTypeId = 1, DataType = "Test" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((TemplateDataType)null);

			// Act
			var result = await _controller.UpdateTemplateDataType(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new TemplateDataType { TemplateDataTypeId = 1, DataType = "OldText" };
			var dto = new TemplateDataType { TemplateDataTypeId = 1, DataType = "NewText" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<TemplateDataType>()))
                     .ReturnsAsync((TemplateDataType v) => v);

            // Act
            var result = await _controller.UpdateTemplateDataType(dto);

            // Assert
			Assert.Equal("NewText", existing.DataType);
		}

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
			// Arrange
			var existing = new TemplateDataType { TemplateDataTypeId = 1, DataType = "Text" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

			// Act
			var result = await _controller.DeleteTemplateDataType(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
