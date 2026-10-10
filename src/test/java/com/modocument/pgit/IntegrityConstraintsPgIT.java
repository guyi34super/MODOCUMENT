package com.modocument.pgit;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V30 integrity constraints on the real migrated PostgreSQL schema (ws05,
 * gate C6). The unique constraints and the simple checks are mirrored onto H2
 * through the entities and proven there by IntegrityGateC6Test; the pieces H2
 * cannot express the same way — the enum-vocabulary CHECKs on the nullable
 * IngestionJob step/status columns and ExtractionJob.SourceId NOT NULL — are
 * proven here, straight at the SQL layer as the schema owner (no app code in
 * the path at all).
 *
 * <p>Every test runs in its own rolled-back transaction, so the shared
 * per-JVM database stays clean for the other PgIT classes.
 */
class IntegrityConstraintsPgIT extends PgItSupport {

    private static final int DEFAULT_BANK = 1; // seeded by V10

    @Test
    void roleVocabularyIsClosedAtTheSchema() throws Exception {
        inRolledBackTx(owner -> assertThatThrownBy(() ->
                insertUser(owner, "SUPERUSER"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_appusers_role"));
    }

    @Test
    void ingestionJobStepAndStatusVocabulariesAreClosedAtTheSchema() throws Exception {
        inRolledBackTx(owner -> {
            int user = insertUser(owner, "READ_WRITE");
            int template = insertTemplate(owner, user, "pgit-" + UUID.randomUUID());

            assertThatThrownBy(() -> insertIngestionJob(owner, user, template,
                    "EXPLODED", "UPLOADED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_ingestionjob_status");
            assertThatThrownBy(() -> insertIngestionJob(owner, user, template,
                    "PENDING", "TELEPORTED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_ingestionjob_currentstep");
            // NULL stays allowed on the nullable step columns; the vocabulary
            // check only refuses out-of-range values.
            insertIngestionJob(owner, user, template, "PENDING", null);
        });
    }

    @Test
    void duplicateTemplateFieldsAndTemplateNamesAreRefused() throws Exception {
        inRolledBackTx(owner -> {
            int user = insertUser(owner, "READ_WRITE");
            String name = "pgit-" + UUID.randomUUID();
            int template = insertTemplate(owner, user, name);

            assertThatThrownBy(() -> insertTemplate(owner, user, name))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_template_owneruserid_name");

            insertTemplateField(owner, template, "Amount");
            assertThatThrownBy(() -> insertTemplateField(owner, template, "Amount"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_templatefields_templateid_fieldname");
        });
    }

    @Test
    void negativeUploadSizesAndCountersAreRefused() throws Exception {
        inRolledBackTx(owner -> {
            int user = insertUser(owner, "READ_WRITE");
            assertThatThrownBy(() -> execute(owner,
                    "INSERT INTO UploadedFile (StoredName, OriginalName, OwnerUserId, BankId, "
                            + "SizeBytes) VALUES ('pgit-" + UUID.randomUUID() + "', 'x.csv', "
                            + user + ", " + DEFAULT_BANK + ", -1)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_uploadedfile_sizebytes");
        });
    }

    @Test
    void extractionJobsRequireASourceAtTheSchema() throws Exception {
        inRolledBackTx(owner -> {
            int user = insertUser(owner, "READ_WRITE");
            assertThatThrownBy(() -> execute(owner,
                    "INSERT INTO ExtractionJob (OwnerUserId, BankId, SourceId, Status) VALUES ("
                            + user + ", " + DEFAULT_BANK + ", NULL, 'PENDING')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("sourceid");
            assertThatThrownBy(() -> execute(owner,
                    "INSERT INTO ExtractionJob (OwnerUserId, BankId, SourceId, Status) VALUES ("
                            + user + ", " + DEFAULT_BANK + ", 'src-1', 'VAPORIZED')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_extractionjob_status");
        });
    }

    // ------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface TxBody {
        void run(Connection owner) throws Exception;
    }

    /**
     * Owner connection, autocommit off, always rolled back. PostgreSQL aborts
     * the transaction on the first error, so each statement that must fail
     * runs inside its own SAVEPOINT (see {@link #execute}).
     */
    private static void inRolledBackTx(TxBody body) throws Exception {
        try (Connection owner = ownerDataSource().getConnection()) {
            owner.setAutoCommit(false);
            try {
                body.run(owner);
            } finally {
                owner.rollback();
            }
        }
    }

    /** Runs one statement under a savepoint so a refusal aborts only itself. */
    private static void execute(Connection connection, String sql) throws SQLException {
        var savepoint = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            connection.rollback(savepoint);
            throw e;
        }
    }

    private static int insertUser(Connection owner, String role) throws SQLException {
        return insertReturningId(owner,
                "INSERT INTO AppUsers (Email, PasswordHash, DisplayName, BankId, Role) VALUES "
                        + "('pgit-" + UUID.randomUUID() + "@example.com', 'x', 'pgit', "
                        + DEFAULT_BANK + ", '" + role + "') RETURNING AppUserId");
    }

    private static int insertTemplate(Connection owner, int userId, String name)
            throws SQLException {
        return insertReturningId(owner,
                "INSERT INTO Template (Name, Description, OwnerUserId, BankId) VALUES ('"
                        + name + "', 'pgit', " + userId + ", " + DEFAULT_BANK
                        + ") RETURNING TemplateId");
    }

    private static void insertTemplateField(Connection owner, int templateId, String fieldName)
            throws SQLException {
        execute(owner, "INSERT INTO TemplateFields (TemplateId, BankId, FieldName, "
                + "TemplateDataTypeId) VALUES (" + templateId + ", " + DEFAULT_BANK + ", '"
                + fieldName + "', 1)"); // data type 1 = Text, seeded by V2
    }

    private static void insertIngestionJob(Connection owner, int userId, int templateId,
                                           String status, String currentStep) throws SQLException {
        String step = currentStep == null ? "NULL" : "'" + currentStep + "'";
        execute(owner, "INSERT INTO IngestionJob (TemplateId, OwnerUserId, BankId, FileName, "
                + "Status, CurrentStep) VALUES (" + templateId + ", " + userId + ", "
                + DEFAULT_BANK + ", 'f.csv', '" + status + "', " + step + ")");
    }

    private static int insertReturningId(Connection owner, String sql) throws SQLException {
        var savepoint = owner.setSavepoint();
        try (PreparedStatement statement = owner.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            assertThat(rs.next()).isTrue();
            owner.releaseSavepoint(savepoint);
            return rs.getInt(1);
        } catch (SQLException e) {
            owner.rollback(savepoint);
            throw e;
        }
    }
}
