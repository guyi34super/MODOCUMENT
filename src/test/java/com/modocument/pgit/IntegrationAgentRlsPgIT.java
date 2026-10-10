package com.modocument.pgit;

import com.modocument.application.dto.AgentProtocol;
import com.modocument.application.port.integration.ApiClientIssuer;
import com.modocument.application.usecase.AgentEnrolmentService;
import com.modocument.application.usecase.AgentEnrolmentToken;
import com.modocument.domain.model.integration.ApiAuthMethod;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * ws26 Connector Agents on the REAL PostgreSQL schema (V261) with row level
 * security enforced (app booted on {@code modocument_app}, ddl-auto=validate,
 * so the IntegrationAgent mapping is checked against the migration):
 * <ul>
 *   <li>the IntegrationAgent table is under the tenant policy — another bank
 *       sees none of bank A's agents and cannot write one into bank A;</li>
 *   <li>the public enrolment resolves the token's agent through its
 *       registered {@code runAsSystem} read, then enrols as bank A under RLS:
 *       the agent row and the AGENT_ENROLLED audit row land in bank A.</li>
 * </ul>
 * The ApiClientIssuer (SE2, parallel Wave 3 branch) is mocked.
 */
class IntegrationAgentRlsPgIT extends PgSpringItSupport {

    private static final int BANK_A = 526;
    private static final int BANK_B = 626;
    private static final int AGENT_A = 7526;
    private static final int MAKER_A = 9526;

    private static final AgentEnrolmentToken TOKEN = AgentEnrolmentToken.generate(AGENT_A);
    private static boolean seeded;

    @Autowired
    private AgentEnrolmentService enrolment;

    @MockitoBean
    private ApiClientIssuer issuer;

    @BeforeAll
    static void seedAsOwner() throws Exception {
        if (seeded) {
            return;
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("INSERT INTO Bank (BankId, Code, Name) VALUES "
                    + "(" + BANK_A + ", 'AGT_A', 'Agent Bank A'), "
                    + "(" + BANK_B + ", 'AGT_B', 'Agent Bank B') ON CONFLICT DO NOTHING");
            try (PreparedStatement insert = owner.prepareStatement("INSERT INTO IntegrationAgent "
                    + "(IntegrationAgentId, BankId, Name, Status, EnrolmentTokenHash, "
                    + "EnrolmentExpiresAt, CreatedByUserId, CreatedAt) VALUES "
                    + "(?, ?, 'pgit agent', 'PENDING_ENROLMENT', ?, now() + interval '10 minutes', ?, now()) "
                    + "ON CONFLICT DO NOTHING")) {
                insert.setInt(1, AGENT_A);
                insert.setInt(2, BANK_A);
                insert.setString(3, AgentEnrolmentToken.hash(TOKEN.secret()));
                insert.setInt(4, MAKER_A);
                insert.executeUpdate();
            }
        }
        seeded = true;
    }

    @Test
    void anotherBankNeitherSeesNorWritesBankAsAgents() throws Exception {
        try (Connection app = appDataSource().getConnection()) {
            app.setAutoCommit(false);
            try {
                setTenant(app, String.valueOf(BANK_B));
                assertThat(count(app, AGENT_A)).isZero();
                assertThatThrownBy(() -> {
                    try (Statement statement = app.createStatement()) {
                        statement.execute("INSERT INTO IntegrationAgent (BankId, Name, Status, "
                                + "CreatedByUserId, CreatedAt) VALUES (" + BANK_A
                                + ", 'forged', 'ONLINE', 1, now())");
                    }
                }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            } finally {
                app.rollback();
            }
            setTenant(app, String.valueOf(BANK_A));
            assertThat(count(app, AGENT_A)).isEqualTo(1);
            app.rollback();
        }
    }

    @Test
    void enrolmentResolvesTheBankAsSystemThenWritesAsThatBank() throws Exception {
        when(issuer.issueAgentClient(eq(BANK_A), eq(AGENT_A), eq("pgit agent"),
                eq(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS), isNull()))
                .thenReturn(new ApiClientIssuer.Issued(31, "agt_pgit", "once",
                        ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS));

        AgentProtocol.EnrollResponse response = enrolment.enroll(
                new AgentProtocol.EnrollRequest(TOKEN.value(), "pgit-host", "1.0.0", null, null),
                "10.66.0.1", "https://modocument.test/api/integration/oauth/token");
        assertThat(response.agentId()).isEqualTo(AGENT_A);
        assertThat(response.clientId()).isEqualTo("agt_pgit");

        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT Status, ApiClientId, EnrolmentTokenHash, "
                     + "Version FROM IntegrationAgent WHERE IntegrationAgentId = " + AGENT_A)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("ONLINE");
            assertThat(rs.getInt(2)).isEqualTo(31);
            assertThat(rs.getString(3)).isNull();
            assertThat(rs.getString(4)).isEqualTo("1.0.0");
        }
        try (Connection owner = ownerDataSource().getConnection();
             Statement statement = owner.createStatement();
             ResultSet rs = statement.executeQuery("SELECT BankId FROM AuditEvent WHERE Action = "
                     + "'AGENT_ENROLLED' AND EntityId = '" + AGENT_A + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(BANK_A);
        }

        // Single use, under RLS too.
        assertThatThrownBy(() -> enrolment.enroll(
                new AgentProtocol.EnrollRequest(TOKEN.value(), "pgit-host", "1.0.0", null, null),
                "10.66.0.1", "https://modocument.test/api/integration/oauth/token"))
                .isInstanceOf(AgentEnrolmentService.InvalidEnrolmentTokenException.class);
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.tenant', ?, true)")) {
            statement.setString(1, tenant);
            statement.executeQuery().close();
        }
    }

    private static int count(Connection connection, int agentId) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT count(*) FROM IntegrationAgent WHERE IntegrationAgentId = " + agentId)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
