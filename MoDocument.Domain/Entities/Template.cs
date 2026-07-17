using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
	public class Template
	{
		[Key]
		public int TemplateId { get; set; }
		public string Name { get; set; }
        public string Description { get; set; }
    }
}
