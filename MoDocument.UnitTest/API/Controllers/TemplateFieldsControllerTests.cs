using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class TemplateFieldsControllerTests
	{
		private readonly Mock<ITemplateFieldsRepository> _mockRepo;
		private readonly TemplateFieldsController _controller;

		public TemplateFieldsControllerTests()
		{
			_mockRepo = new Mock<ITemplateFieldsRepository>();
			_controller = new TemplateFieldsController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfTemplateTypes()
		{
			// Arrange
			var TemplateFields = new List<TemplateFields>
			{
				new TemplateFields { TemplateFieldsId = 1, FieldName = "TemplateFieldsType1" },
				new TemplateFields { TemplateFieldsId = 2, FieldName = "TemplateFieldsType2" }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(TemplateFields);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<TemplateFields>>(okResult.Value);
			Assert.Equal(2, ((List<TemplateFields>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((TemplateFields)null);

			// Act
			var result = await _controller.GetTemplateFieldsById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithTemplateFields()
		{
			// Arrange
			var TemplateFields = new TemplateFields { TemplateFieldsId = 1, FieldName = "TemplateFieldsType1" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(TemplateFields);

			// Act
			var result = await _controller.GetTemplateFieldsById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<TemplateFields>(okResult.Value);
			Assert.Equal(TemplateFields.TemplateFieldsId, returnValue.TemplateFieldsId);
			Assert.Equal(TemplateFields.FieldName, returnValue.FieldName);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewTemplateFields()
		{
            // Arrange
            var dto = new TemplateFields { FieldName = "NewTemplateFieldName" };
            _mockRepo.Setup(repo => repo.AddAsync(It.IsAny<TemplateFields>())).Callback<TemplateFields>(t => t.TemplateFieldsId = 1);

            // Act
            var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<TemplateFields>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal("NewTemplateFieldName", returnValue.FieldName);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new TemplateFields { TemplateFieldsId = 2, FieldName = "TestType"};

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenTemplateFieldsDoesNotExist()
		{
			// Arrange
			var dto = new TemplateFields { TemplateFieldsId = 1, FieldName = "TestName" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((TemplateFields)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new TemplateFields { TemplateFieldsId = 1, FieldName = "OldName" };
			var dto = new TemplateFields { TemplateFieldsId = 1, FieldName = "NewName" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<TemplateFields>()))
                     .ReturnsAsync((TemplateFields v) => v);

            // Act
            var result = await _controller.Update(dto);

            // Assert
			Assert.Equal("NewName", existing.FieldName);
        }

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
            // Arrange
			var existing = new TemplateFields { TemplateFieldsId = 1, FieldName = "OldName" };
            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

            // Act
            var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
