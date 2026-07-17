using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
	public class Validations
    {
        [Key]
        public int ValidationId { get; set; }
        public string Name { get; set; }
        public int TemplateFieldsId { get; set; }
        public string Description { get; set; }

        [ForeignKey("TemplateFieldsId")]
        public virtual TemplateFields? TemplateFields { get; set; }
    }
}
