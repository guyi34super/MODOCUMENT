using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
	public class ImportHistory
	{
		[Key]
		public int ImportHistoryId { get; set; }
		public DateTime ImportDate { get; set; }
		public string FileName { get; set; }
		public int CompanyId { get; set; }
        public int TemplateId { get; set; }

        [ForeignKey("TemplateId")]
        public virtual Template? Template { get; set; }
    }
}
