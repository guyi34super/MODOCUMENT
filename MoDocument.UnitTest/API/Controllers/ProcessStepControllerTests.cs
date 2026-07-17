using Microsoft.AspNetCore.Mvc;
using MoDocument.Application.Interfaces;
using MoDocument.Domain.Entities;
using MoDocument.API.Controllers;
using Moq;

namespace MoDocument.UnitTests.API.Controllers
{
	public class ProcessStepControllerTests
	{
		private readonly Mock<IProcessStepRepository> _mockRepo;
		private readonly ProcessStepController _controller;

		public ProcessStepControllerTests()
		{
			_mockRepo = new Mock<IProcessStepRepository>();
			_controller = new ProcessStepController(_mockRepo.Object);
		}

		[Fact]
		public async Task GetAll_ReturnsOkResult_WithListOfProcessStepTypes()
		{
			// Arrange
			var ProcessStep = new List<ProcessStep>
			{
				new ProcessStep { ProcessStepId = 1, Name = "ProcessStepName1", Description = "ProcessStepDescription1" },
				new ProcessStep { ProcessStepId = 2, Name = "ProcessStepName2", Description = "ProcessStepDescription2" }
			};
			_mockRepo.Setup(repo => repo.ListAsync()).ReturnsAsync(ProcessStep);

			// Act
			var result = await _controller.GetAll();

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsAssignableFrom<IEnumerable<ProcessStep>>(okResult.Value);
			Assert.Equal(2, ((List<ProcessStep>)returnValue).Count);
		}

		[Fact]
		public async Task Get_ReturnsNotFound_WhenIdDoesNotExist()
		{
			// Arrange
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ProcessStep)null);

			// Act
			var result = await _controller.GetProcessStepById(1);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Get_ReturnsOkResult_WithProcessStepType()
		{
			// Arrange
			var ProcessStep = new ProcessStep { ProcessStepId = 1, Name = "ProcessStepName1", Description = "ProcessStepDescription1" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(ProcessStep);

			// Act
			var result = await _controller.GetProcessStepById(1);

			// Assert
			var okResult = Assert.IsType<OkObjectResult>(result);
			var returnValue = Assert.IsType<ProcessStep>(okResult.Value);
			Assert.Equal(ProcessStep.ProcessStepId, returnValue.ProcessStepId);
			Assert.Equal(ProcessStep.Name, returnValue.Name);
			Assert.Equal(ProcessStep.Description, returnValue.Description);
        }

		[Fact]
		public async Task Create_ReturnsCreatedAtActionResult_WithNewProcessStepType()
		{
			// Arrange
			var dto = new ProcessStep { Name = "NewProcessStepName", Description = "NewProcessStepDescription" };
			_mockRepo.Setup(repo => repo.AddAsync(It.IsAny<ProcessStep>()))
					 .Returns(Task.CompletedTask)
					 .Callback<ProcessStep>(t => t.ProcessStepId = 1);

			// Act
			var result = await _controller.Create(dto);

			// Assert
			var createdAt = Assert.IsType<CreatedAtActionResult>(result);
			var returnValue = Assert.IsType<ProcessStep>(createdAt.Value);
			//Assert.Equal(1, returnValue.Id);
			Assert.Equal("NewProcessStepName", returnValue.Name);
			Assert.Equal("NewProcessStepDescription", returnValue.Description);
        }

		//[Fact]
		//public async Task Update_ReturnsBadRequest_WhenIdMismatch()
		//{
		//	// Arrange
		//	var dto = new ProcessStep { ProcessStepId = 2, Name = "TestName", Description = "TestDescription"};

		//	// Act
		//	var result = await _controller.Update(dto);

		//	// Assert
		//	Assert.IsType<BadRequestObjectResult>(result);
		//}

		[Fact]
		public async Task Update_ReturnsNotFound_WhenProcessStepTypeDoesNotExist()
		{
			// Arrange
			var dto = new ProcessStep { ProcessStepId = 1, Name = "TestName", Description = "TestDescription" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync((ProcessStep)null);

			// Act
			var result = await _controller.Update(dto);

			// Assert
			Assert.IsType<NotFoundResult>(result);
		}

		[Fact]
		public async Task Update_ReturnsUpdatedContent_WhenUpdateIsSuccessful()
		{
			// Arrange
			var existing = new ProcessStep { ProcessStepId = 1, Name = "OldName", Description = "OldDescription" };
			var dto = new ProcessStep { ProcessStepId = 1, Name = "NewName", Description = "NewDescription" };

            _mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.UpdateAsync(It.IsAny<ProcessStep>()))
                     .ReturnsAsync((ProcessStep v) => v);

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
			var existing = new ProcessStep { ProcessStepId = 1, Name = "ProcessStepName", Description = "ProcessStepDescription" };
			_mockRepo.Setup(repo => repo.GetByIdAsync(1)).ReturnsAsync(existing);
            _mockRepo.Setup(repo => repo.DeleteAsync(1)).Returns(Task.CompletedTask);

			// Act
			var result = await _controller.Delete(1);

			// Assert
			Assert.IsType<NoContentResult>(result);
		}
	}
}
