package com.modocument.domain.entity;

import com.modocument.domain.model.integration.FlowDirection;
import com.modocument.domain.model.integration.FlowEvent;
import com.modocument.domain.model.integration.FlowTrigger;
import com.modocument.domain.model.integration.PayloadFormat;
import com.modocument.domain.model.integration.PayloadKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What to receive or send, and when (ws25, V250). Specs hold identifiers and
 * relative paths only (validated by the connector on save and before every
 * run); {@code Events}/{@code PayloadKinds} are comma-separated enum names.
 * An OUTBOUND flow runs only while {@code Enabled} — enabling it (or
 * widening an enabled one) is maker/checker, and {@code ApprovedVersion}
 * records the flow version a second admin approved.
 *
 * <p>SCHEMA PARITY: mirrors V250 on the H2 test schema (ddl-auto: create).
 */
@Entity
@Table(name = "IntegrationFlow",
        uniqueConstraints = @UniqueConstraint(name = "uq_integrationflow_bankid_name",
                columnNames = {"BankId", "Name"}),
        indexes = {
                @Index(name = "ix_integrationflow_connectionid", columnList = "ConnectionId"),
                @Index(name = "ix_integrationflow_bankid_enabled", columnList = "BankId, Enabled")})
@Check(name = "ck_integrationflow_direction", constraints = "Direction in ('INBOUND', 'OUTBOUND')")
@Check(name = "ck_integrationflow_triggertype",
        constraints = "TriggerType in ('MANUAL', 'SCHEDULE', 'EVENT')")
@Check(name = "ck_integrationflow_format", constraints = "Format in ('JSON', 'CSV', 'XLSX', 'PDF')")
@Check(name = "ck_integrationflow_rowcap", constraints = "RowCap IS NULL OR RowCap > 0")
public class IntegrationFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "IntegrationFlowId")
    private Long id;

    @Column(name = "BankId", nullable = false)
    private int bankId;

    @Column(name = "Name", length = 100, nullable = false)
    private String name;

    @Column(name = "ConnectionId", nullable = false)
    private long connectionId;

    @Column(name = "TemplateId")
    private Integer templateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "Direction", length = 10, nullable = false)
    private FlowDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "TriggerType", length = 10, nullable = false)
    private FlowTrigger triggerType;

    @Column(name = "CronExpression", length = 120)
    private String cronExpression;

    @Column(name = "Events", length = 200)
    private String events;

    @ColumnDefault("''")
    @Column(name = "PayloadKinds", length = 200, nullable = false)
    private String payloadKinds = "";

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'JSON'")
    @Column(name = "Format", length = 10, nullable = false)
    private PayloadFormat format = PayloadFormat.JSON;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "SourceSpecJson")
    private String sourceSpecJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "TargetSpecJson")
    private String targetSpecJson;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "FieldMappingJson")
    private String fieldMappingJson;

    @ColumnDefault("true")
    @Column(name = "MaskPii", nullable = false)
    private boolean maskPii = true;

    @Column(name = "RowCap")
    private Integer rowCap;

    @ColumnDefault("false")
    @Column(name = "Enabled", nullable = false)
    private boolean enabled;

    @Column(name = "ApprovedVersion")
    private Integer approvedVersion;

    @Column(name = "CursorValue", length = 500)
    private String cursorValue;

    @Column(name = "NextRunAt")
    private Instant nextRunAt;

    @Version
    @ColumnDefault("0")
    @Column(name = "Version", nullable = false)
    private int version;

    @Column(name = "CreatedBy", nullable = false)
    private int createdBy;

    @Column(name = "CreatedAt", nullable = false)
    private Instant createdAt;

    @Column(name = "UpdatedAt")
    private Instant updatedAt;

    // ---- typed views of the comma-separated columns -----------------------

    public Set<FlowEvent> eventSet() {
        return parse(events, FlowEvent.class);
    }

    public void setEventSet(Collection<FlowEvent> values) {
        this.events = join(values);
    }

    public Set<PayloadKind> payloadKindSet() {
        return parse(payloadKinds, PayloadKind.class);
    }

    public void setPayloadKindSet(Collection<PayloadKind> values) {
        String joined = join(values);
        this.payloadKinds = joined == null ? "" : joined;
    }

    private static <E extends Enum<E>> Set<E> parse(String csv, Class<E> type) {
        EnumSet<E> set = EnumSet.noneOf(type);
        if (csv == null || csv.isBlank()) {
            return set;
        }
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .forEach(s -> set.add(Enum.valueOf(type, s.toUpperCase(Locale.ROOT))));
        return set;
    }

    private static <E extends Enum<E>> String join(Collection<E> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().sorted().map(Enum::name).distinct().collect(Collectors.joining(","));
    }

    // ---- accessors ---------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getBankId() {
        return bankId;
    }

    public void setBankId(int bankId) {
        this.bankId = bankId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(long connectionId) {
        this.connectionId = connectionId;
    }

    public Integer getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Integer templateId) {
        this.templateId = templateId;
    }

    public FlowDirection getDirection() {
        return direction;
    }

    public void setDirection(FlowDirection direction) {
        this.direction = direction;
    }

    public FlowTrigger getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(FlowTrigger triggerType) {
        this.triggerType = triggerType;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getPayloadKinds() {
        return payloadKinds;
    }

    public void setPayloadKinds(String payloadKinds) {
        this.payloadKinds = payloadKinds == null ? "" : payloadKinds;
    }

    public PayloadFormat getFormat() {
        return format;
    }

    public void setFormat(PayloadFormat format) {
        this.format = format == null ? PayloadFormat.JSON : format;
    }

    public String getSourceSpecJson() {
        return sourceSpecJson;
    }

    public void setSourceSpecJson(String sourceSpecJson) {
        this.sourceSpecJson = sourceSpecJson;
    }

    public String getTargetSpecJson() {
        return targetSpecJson;
    }

    public void setTargetSpecJson(String targetSpecJson) {
        this.targetSpecJson = targetSpecJson;
    }

    public String getFieldMappingJson() {
        return fieldMappingJson;
    }

    public void setFieldMappingJson(String fieldMappingJson) {
        this.fieldMappingJson = fieldMappingJson;
    }

    public boolean isMaskPii() {
        return maskPii;
    }

    public void setMaskPii(boolean maskPii) {
        this.maskPii = maskPii;
    }

    public Integer getRowCap() {
        return rowCap;
    }

    public void setRowCap(Integer rowCap) {
        this.rowCap = rowCap;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getApprovedVersion() {
        return approvedVersion;
    }

    public void setApprovedVersion(Integer approvedVersion) {
        this.approvedVersion = approvedVersion;
    }

    public String getCursorValue() {
        return cursorValue;
    }

    public void setCursorValue(String cursorValue) {
        this.cursorValue = cursorValue;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(int createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
