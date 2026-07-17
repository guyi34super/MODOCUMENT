using DocumentFormat.OpenXml.Office2016.Drawing.ChartDrawing;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi.Validations;
using MoDocument.API.Controllers;
using MoDocument.Application.Interfaces;
using MoDocument.Application.UseCases;
using MoDocument.Domain.Interfaces;
using MoDocument.Infrastructure.Persistence;
using MoDocument.Infrastructure.Prompts;
using MoDocument.Infrastructure.Repositories;
using MoDocument.Infrastructure.Services;
using MoDocument.Infrastructure.ValidationRules;
using System.Reflection;

var builder = WebApplication.CreateBuilder(args);
builder.Configuration.AddJsonFile("appsettings.Local.json", optional: true, reloadOnChange: true);

// In MyApp.API/Program.cs, before builder.Build()
//builder.Services.AddDbContext<AppDbContext>(opt =>
//    opt.UseSqlServer(builder.Configuration.GetConnectionString("Default")));
builder.Services.AddDbContext<AppDbContext>(options =>
{
    options.UseLazyLoadingProxies();
    options.EnableSensitiveDataLogging();

    options.UseSqlServer(builder.Configuration.GetConnectionString("Default"),
        sqlServerOptionsAction: sqlOptions =>
        {
            sqlOptions.MigrationsAssembly("MoDocument.Infrastructure");
        });

});

builder.Services.AddScoped<ITemplateDataTypeRepository, TemplateDataTypeRepository>();
builder.Services.AddScoped<ITemplateRepository, TemplateRepository>();
builder.Services.AddScoped<ITemplateFieldsRepository, TemplateFieldsRepository>();
builder.Services.AddScoped<IImportHistoryRepository, ImportHistoryRepository>();
builder.Services.AddScoped<IImportDetailsRepository, ImportDetailsRepository>();
builder.Services.AddScoped<IProcessStepRepository, ProcessStepRepository>();
builder.Services.AddScoped<IAIfilesService, AIfilesService>();
builder.Services.AddScoped<ITransformService, TransformService>();
builder.Services.AddTransient<IPromptStrategy, TemplateIdentifierPrompt>();
builder.Services.AddTransient<IPromptStrategy, ExtractionPrompt>();
builder.Services.AddTransient<PromptFactory>();

// Validation Engine
// DI for repository and use case
builder.Services.AddScoped<IValidationRuleRepository, ValidationRuleRepository>();
builder.Services.AddScoped<IValidateRowsUseCase, ValidateRowsUseCase>();

// DI for validators
builder.Services.AddScoped<IValidationRule, RequiredRule>();
builder.Services.AddScoped<IValidationRule, RegexRule>();
builder.Services.AddScoped<IValidationRule, RangeRule>();
builder.Services.AddScoped<IValidationRule, DateFormatRule>();
builder.Services.AddScoped<IValidationRule, ExpiryDateRule>();

// Add CORS Configuration
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowReactApp", policy =>
    {
        policy
            .WithOrigins(
                "http://localhost:3000",
                "http://localhost:8004",
                "http://localhost:8080",
                "https://localhost:3000",
                "https://localhost:8004",
                "https://localhost:8080"
            )
            .AllowAnyMethod()
            .AllowAnyHeader()
            .AllowCredentials();
    });

    options.AddPolicy("AllowAllForDevelopment", policy =>
    {
        policy
            .AllowAnyOrigin()
            .AllowAnyMethod()
            .AllowAnyHeader();
    });
});

builder.Services.AddControllers();
// Learn more about configuring Swagger/OpenAPI at https://aka.ms/aspnetcore/swashbuckle
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.UseStaticFiles(new StaticFileOptions { ServeUnknownFileTypes = true });
    app.UseSwagger();
    app.UseSwaggerUI(c =>
    {
        c.SwaggerEndpoint("/swagger/spec.yaml", "MoDocument Service");
    });
}

app.UseHttpsRedirection();

// Use CORS
app.UseCors("AllowReactApp");

app.UseAuthorization();

app.MapControllers();

app.Run();