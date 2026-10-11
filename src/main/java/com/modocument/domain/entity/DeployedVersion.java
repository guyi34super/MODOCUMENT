package com.modocument.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The first start of a release version in this environment (ws24, V240):
 * written once per version by {@code DeployedVersionRecorder} — the primary
 * key on Version makes the first task of a rolling deploy the only writer —
 * and read by {@code SystemVersionService} as {@code deployedAt}.
 *
 * <p>NON-TENANT: no BankId (a deployment belongs to the installation, and
 * the row holds no customer data), so row level security does not apply.
 *
 * <p>SCHEMA PARITY: the test profile builds its H2 schema from this entity
 * (ddl-auto: create, Flyway disabled), so it mirrors
 * V240__ws24_deployed_version.sql by hand.
 */
@Entity
@Table(name = "DeployedVersion")
public class DeployedVersion {

    @Id
    @Column(name = "Version", length = 64, nullable = false)
    private String version;

    @Column(name = "GitCommit", length = 40)
    private String gitCommit;

    @Column(name = "DeployedAt", nullable = false)
    private Instant deployedAt;

    @Column(name = "Environment", length = 32, nullable = false)
    private String environment;

    protected DeployedVersion() {
    }

    public DeployedVersion(String version, String gitCommit, Instant deployedAt, String environment) {
        this.version = version;
        this.gitCommit = gitCommit;
        this.deployedAt = deployedAt;
        this.environment = environment;
    }

    public String getVersion() {
        return version;
    }

    public String getGitCommit() {
        return gitCommit;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }

    public String getEnvironment() {
        return environment;
    }
}
