using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
	public class TemplateFields
	{
		[Key]
		public int TemplateFieldsId { get; set; }

		public int TemplateId { get; set; }

        public string FieldName { get; set; }

        public int TemplateDataTypeId { get; set; }

        [ForeignKey("TemplateId")]
		public virtual Template? Template { get; set; }

        [ForeignKey("TemplateDataTypeId")]
        public virtual TemplateDataType? TemplateDataType { get; set; }
    }
}
