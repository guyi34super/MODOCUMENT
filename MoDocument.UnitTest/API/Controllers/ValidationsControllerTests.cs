using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class ValidationsControllerTests
	{
		private readonly Mock<IValidationsRepository> _mockRepo;
		private readonly ValidationsController _controller;

		public ValidationsControllerTests()
		{
			_mockRepo = new Mock<IValidationsRepository>();
			_controller = new ValidationsController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfValidationsTypes()
		{
			// Arrange
			var Validations = new List<Validations>
			{
				new Validations { ValidationId = 1, Name = "ValidationsName1", TemplateFieldsId = 1, Description = "ValidationsDescription1" },
				new Validations { ValidationId = 2, Name = "ValidationsName2", TemplateFieldsId = 2, Description = "ValidationsDescription2" }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(Validations);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<Validations>>(okResult.Value);
			Assert.Equal(2, ((List<Validations>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((Validations)null);

			// Act
			var result = await _controller.GetValidationById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithValidationsType()
		{
			// Arrange
			var Validations = new Validations { ValidationId = 1, Name = "ValidationsName1", TemplateFieldsId = 1, Description = "ValidationsDescription1" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(Validations);

			// Act
			var result = await _controller.GetValidationById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<Validations>(okResult.Value);
			Assert.Equal(Validations.ValidationId, returnValue.ValidationId);
			Assert.Equal(Validations.Name, returnValue.Name);
            Assert.Equal(Validations.TemplateFieldsId, returnValue.TemplateFieldsId);
            Assert.Equal(Validations.Description, returnValue.Description);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewValidationsType()
		{
			// Arrange
			var dto = new Validations { Name = "NewValidationsName", TemplateFieldsId = 1, Description = "NewValidationsDescription" };
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<Validations>())).Callback<Validations>(t => t.ValidationId = 1);

			// Act
			var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<Validations>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal("NewValidationsName", returnValue.Name);
            Assert.Equal(1, returnValue.TemplateFieldsId);
            Assert.Equal("NewValidationsDescription", returnValue.Description);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new Validations { ValidationId = 2, Name = "TestName", TemplateFieldsId = 2, Description = "TestDescription"};

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenValidationsTypeDoesNotExist()
		{
			// Arrange
			var dto = new Validations { ValidationId = 1, Name = "TestName", TemplateFieldsId = 1, Description = "TestDescription" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((Validations)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new Validations { ValidationId = 1, Name = "OldName", TemplateFieldsId = 1, Description = "OldDescription" };
			var dto = new Validations { ValidationId = 1, Name = "NewName", TemplateFieldsId = 2, Description = "NewDescription" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<Validations>()))
                     .ReturnsAsync((Validations v) => v);

            // Act
            var result = await _controller.Update(dto);

            // Assert
            Assert.Equal("NewName", existing.Name);
            Assert.Equal(2, existing.TemplateFieldsId);
            Assert.Equal("NewDescription", existing.Description);
        }

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
			// Arrange
			var existing = new Validations { ValidationId = 1 };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

			// Act
			var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
