package com.modocument.domain.entity;

import jakarta.validation.constraints.Size;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ProcessStep")
public class ProcessStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ProcessStepId")
    private int processStepId;

    @Column(name = "Name")
    @Size(max = 255)
    private String name;

    @Column(name = "Description")
    @Size(max = 255)
    private String description;

    public ProcessStep() {
    }

    public ProcessStep(int processStepId, String name, String description) {
        this.processStepId = processStepId;
        this.name = name;
        this.description = description;
    }

    public int getProcessStepId() {
        return processStepId;
    }

    public void setProcessStepId(int processStepId) {
        this.processStepId = processStepId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
