package com.modocument.infrastructure.extraction.sql.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * One named counter of one extraction job (tasksCompleted, recordsRaw, …).
 * Kept out of the job row so concurrent increments are single-row UPDATEs.
 * Must stay in sync with V8__extraction_sql_store.sql.
 */
@Entity
@Table(name = "ExtractionJobCounter")
@IdClass(ExtractionJobCounterEntity.Key.class)
public class ExtractionJobCounterEntity {

    @Id
    @Column(name = "JobId")
    private long jobId;

    @Id
    @Column(name = "Name", length = 40)
    private String name;

    // "Value" is a reserved word in H2, hence the longer column name.
    @Column(name = "CounterValue", nullable = false)
    private long value;

    public ExtractionJobCounterEntity() {
    }

    public ExtractionJobCounterEntity(long jobId, String name, long value) {
        this.jobId = jobId;
        this.name = name;
        this.value = value;
    }

    public long getJobId() {
        return jobId;
    }

    public void setJobId(long jobId) {
        this.jobId = jobId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getValue() {
        return value;
    }

    public void setValue(long value) {
        this.value = value;
    }

    public static class Key implements Serializable {
        private long jobId;
        private String name;

        public Key() {
        }

        public Key(long jobId, String name) {
            this.jobId = jobId;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other)) {
                return false;
            }
            return jobId == other.jobId && Objects.equals(name, other.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, name);
        }
    }
}
