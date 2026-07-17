-- Schema additions for the resumable ingestion pipeline (SQL Server).
-- The base schema is created manually (see README); run this once against the
-- MoDocument database. H2 profiles create this table automatically from the
-- JPA entity (ddl-auto: create), so this script is for SQL Server only.

CREATE TABLE IngestionJob (
    IngestionJobId       INT IDENTITY (1, 1) NOT NULL PRIMARY KEY,
    TemplateId           INT                 NOT NULL,
    FileName             NVARCHAR(400)       NULL,
    ImportHistoryId      INT                 NULL,
    CurrentStep          VARCHAR(20)         NULL, -- JobStep enum name: UPLOADED/PARSED/MAPPED/VALIDATED/EXPORTED
    Status               VARCHAR(20)         NULL, -- JobStatus enum name: PENDING/RUNNING/COMPLETED/FAILED/CANCELLED
    ErrorMessage         NVARCHAR(2000)      NULL,
    ParsedTableJson      NVARCHAR(MAX)       NULL,
    MappingJson          NVARCHAR(MAX)       NULL,
    MappedRowsJson       NVARCHAR(MAX)       NULL,
    ValidationResultJson NVARCHAR(MAX)       NULL,
    ExportInfoJson       NVARCHAR(MAX)       NULL,
    CreatedDate          DATETIME2           NULL,
    UpdatedDate          DATETIME2           NULL,
    CONSTRAINT FK_IngestionJob_Template FOREIGN KEY (TemplateId) REFERENCES Template (TemplateId),
    CONSTRAINT FK_IngestionJob_ImportHistory FOREIGN KEY (ImportHistoryId) REFERENCES ImportHistory (ImportHistoryId)
);
