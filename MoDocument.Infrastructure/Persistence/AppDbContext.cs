using Microsoft.EntityFrameworkCore;
using MoDocument.Domain.Entities;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Infrastructure.Persistence
{
	public class AppDbContext : DbContext
	{
        public AppDbContext(DbContextOptions<AppDbContext> options) : base(options) { }
		public DbSet<TemplateDataType> TemplateDataTypes { get; set; }
        public DbSet<Template> Template { get; set; }
        public DbSet<TemplateFields> TemplateFields { get; set; }
        public DbSet<ImportDetails> ImportDetails { get; set; }
        public DbSet<ImportHistory> ImportHistory { get; set; }
        public DbSet<ProcessStep> ProcessStep { get; set; }
        public DbSet<ValidationRule> ValidationRules { get; set; } = null!;


        protected override void OnModelCreating(ModelBuilder modelBuilder)
		{
			base.OnModelCreating(modelBuilder);
            // existing key + config for Validation Engine
            modelBuilder.Entity<ValidationRule>()
                .HasKey(r => new { r.TemplateId, r.FieldName, r.RuleType });
            modelBuilder.Entity<ValidationRule>()
                .Property(r => r.ConfigJson)
                .IsRequired();
            new DbInitializer(modelBuilder).Seed();
		}
	}
}
