using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class TemplateControllerTests
	{
		private readonly Mock<ITemplateRepository> _mockRepo;
		private readonly TemplateController _controller;

		public TemplateControllerTests()
		{
			_mockRepo = new Mock<ITemplateRepository>();
			_controller = new TemplateController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfTemplates()
		{
			// Arrange
			var Template = new List<Template>
			{
				new Template { TemplateId = 1, Name = "TemplateName1", Description = "TemplateDescription1" },
				new Template { TemplateId = 2, Name = "TemplateName2", Description = "TemplateDescription2" }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(Template);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<Template>>(okResult.Value);
			Assert.Equal(2, ((List<Template>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((Template)null);

			// Act
			var result = await _controller.GetTemplateById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithTemplate()
		{
			// Arrange
			var Template = new Template { TemplateId = 1, Name = "TemplateName1", Description = "TemplateDescription1" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(Template);

			// Act
			var result = await _controller.GetTemplateById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<Template>(okResult.Value);
			Assert.Equal(Template.TemplateId, returnValue.TemplateId);
			Assert.Equal(Template.Name, returnValue.Name);
			Assert.Equal(Template.Description, returnValue.Description);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewTemplate()
		{
			// Arrange
			var dto = new Template { Name = "NewTemplateName", Description = "NewTemplateDescription" };
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<Template>()))
					 .Returns(Task.CompletedTask)
					 .Callback<Template>(t => t.TemplateId = 1);

			// Act
			var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<Template>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal("NewTemplateName", returnValue.Name);
			Assert.Equal("NewTemplateDescription", returnValue.Description);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new Template { TemplateId = 2, Name = "TestName", Description = "TestDescription"};

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenTemplateDoesNotExist()
		{
			// Arrange
			var dto = new Template { TemplateId = 1, Name = "TestName", Description = "TestDescription" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((Template)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new Template { TemplateId = 1, Name = "OldName", Description = "OldDescription" };
			var dto = new Template { TemplateId = 1, Name = "NewName", Description = "NewDescription" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<Template>()))
                     .ReturnsAsync((Template v) => v);

            // Act
            var result = await _controller.Update(dto);

			// Assert
			Assert.Equal("NewName", existing.Name);
            Assert.Equal("NewDescription", existing.Description);
        }

		[Fact]
		public async Task Delete_ReturnsNoContent()
		{
            // Arrange
			var existing = new Template { TemplateId = 1 };
            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

            // Act
            var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
