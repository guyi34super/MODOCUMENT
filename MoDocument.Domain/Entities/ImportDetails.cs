using System;
using System.Collections.Generic;
using System.ComponentModel.DataAnnotations;
using System.ComponentModel.DataAnnotations.Schema;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace MoDocument.Domain.Entities
{
    public class ImportDetails
    {
        [Key]
        public int ImportDetailsId { get; set; }
        public int ImportHistoryId { get; set; }
        public int ProcessStepId { get; set; }
        public string RawJson { get; set; }

        [ForeignKey("ImportHistoryId")]
        public virtual ImportHistory? ImportHistory { get; set; }
        [ForeignKey("ProcessStepId")]
        public virtual ProcessStep? ProcessStep { get; set; }
    }
}
