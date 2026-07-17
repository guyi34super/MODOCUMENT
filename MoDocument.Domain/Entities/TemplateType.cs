using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
	public class TemplateDataType
	{
		[Key]
		public int TemplateDataTypeId { get; set; }
		public string DataType { get; set; }
	}
}
