package com.labflow.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
class DatabaseMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Test
	void applicationContextLoadsAgainstContainerizedPostgres() {
		assertThat(jdbcTemplate).isNotNull();
	}

	@Test
	void flywayAppliesAllMigrationsSuccessfully() {
		List<String> appliedVersions = jdbcTemplate.queryForList("""
				SELECT version
				FROM flyway_schema_history
				WHERE success = TRUE
				ORDER BY installed_rank
				""", String.class);

		assertThat(appliedVersions).contains("1", "2", "3", "4", "5", "6");
	}

	@Test
	void flywayMigrationsCreateExpectedDomainTables() {
		List<String> tables = jdbcTemplate.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN (
				      'app_users', 'experiment_configs', 'idempotency_records', 'job_events',
				      'jobs', 'molecular_inputs', 'outbox_events', 'project_members', 'projects'
				  )
				ORDER BY table_name
				""", String.class);

		assertThat(tables).containsExactly(
				"app_users", "experiment_configs", "idempotency_records", "job_events",
				"jobs", "molecular_inputs", "outbox_events", "project_members", "projects"
		);
	}

	@Test
	void jobSubmissionTablesEnforceReliabilityConstraints() {
		List<String> jobConstraints = constraintsFor("jobs");
		assertThat(jobConstraints).contains(
				"chk_jobs_status",
				"chk_jobs_spec_snapshot_object",
				"chk_jobs_max_attempts",
				"chk_jobs_version",
				"fk_jobs_project",
				"fk_jobs_molecular_input",
				"fk_jobs_experiment_config",
				"fk_jobs_submitted_by"
		);

		List<String> idempotencyConstraints = constraintsFor("idempotency_records");
		assertThat(idempotencyConstraints).contains(
				"uq_idempotency_records_scope_key",
				"uq_idempotency_records_job",
				"chk_idempotency_records_request_hash"
		);

		List<String> eventConstraints = constraintsFor("job_events");
		assertThat(eventConstraints).contains(
				"fk_job_events_job",
				"chk_job_events_from_status",
				"chk_job_events_to_status",
				"chk_job_events_state_change"
		);
	}

	@Test
	void outboxHasACompactUnpublishedEventIndex() {
		String indexDefinition = jdbcTemplate.queryForObject("""
				SELECT indexdef
				FROM pg_indexes
				WHERE schemaname = 'public'
				  AND tablename = 'outbox_events'
				  AND indexname = 'idx_outbox_events_unpublished'
				""", String.class);

		assertThat(indexDefinition)
				.contains("available_at", "id")
				.containsIgnoringCase("WHERE (published_at IS NULL)");
	}

	private List<String> constraintsFor(String tableName) {
		return jdbcTemplate.queryForList("""
				SELECT constraint_name
				FROM information_schema.table_constraints
				WHERE table_schema = 'public'
				  AND table_name = ?
				""", String.class, tableName);
	}

	@Test
	void experimentConfigsAreImmutableProjectScopedVersions() {
		List<String> uniqueColumns = jdbcTemplate.queryForList("""
				SELECT kcu.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				WHERE tc.constraint_type = 'UNIQUE'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'experiment_configs'
				  AND tc.constraint_name = 'uq_experiment_configs_project_name_version'
				ORDER BY kcu.ordinal_position
				""", String.class);

		assertThat(uniqueColumns).containsExactly("project_id", "name", "version");

		String specType = jdbcTemplate.queryForObject("""
				SELECT data_type
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name = 'experiment_configs'
				  AND column_name = 'spec_json'
				""", String.class);

		assertThat(specType).isEqualTo("jsonb");
	}

	@Test
	void molecularInputsReferencesProjectsWithProjectScopedDeduplication() {
		Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
				SELECT
				    tc.constraint_name,
				    kcu.column_name,
				    ccu.table_name AS referenced_table,
				    ccu.column_name AS referenced_column
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				JOIN information_schema.constraint_column_usage ccu
				  ON tc.constraint_catalog = ccu.constraint_catalog
				 AND tc.constraint_schema = ccu.constraint_schema
				 AND tc.constraint_name = ccu.constraint_name
				WHERE tc.constraint_type = 'FOREIGN KEY'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'molecular_inputs'
				  AND tc.constraint_name = 'fk_molecular_inputs_project'
				""");

		assertThat(foreignKey)
				.containsEntry("column_name", "project_id")
				.containsEntry("referenced_table", "projects")
				.containsEntry("referenced_column", "id");

		List<String> uniqueColumns = jdbcTemplate.queryForList("""
				SELECT kcu.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				WHERE tc.constraint_type = 'UNIQUE'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'molecular_inputs'
				  AND tc.constraint_name = 'uq_molecular_inputs_project_sha256'
				ORDER BY kcu.ordinal_position
				""", String.class);

		assertThat(uniqueColumns).containsExactly("project_id", "sha256");
	}

	@Test
	void molecularInputsConstrainsChecksumsSizesAndArtifactPaths() {
		List<String> constraints = jdbcTemplate.queryForList("""
				SELECT constraint_name
				FROM information_schema.table_constraints
				WHERE table_schema = 'public'
				  AND table_name = 'molecular_inputs'
				""", String.class);

		assertThat(constraints).contains(
				"chk_molecular_inputs_sha256",
				"chk_molecular_inputs_size",
				"uq_molecular_inputs_artifact_path"
		);
	}

	@Test
	void projectsOwnerReferencesAppUsersPrimaryKey() {
		Map<String, Object> foreignKey = jdbcTemplate.queryForMap("""
				SELECT
				    tc.constraint_name,
				    kcu.column_name,
				    ccu.table_name AS referenced_table,
				    ccu.column_name AS referenced_column
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				JOIN information_schema.constraint_column_usage ccu
				  ON tc.constraint_catalog = ccu.constraint_catalog
				 AND tc.constraint_schema = ccu.constraint_schema
				 AND tc.constraint_name = ccu.constraint_name
				WHERE tc.constraint_type = 'FOREIGN KEY'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'projects'
				  AND kcu.column_name = 'owner_id'
				""");

		assertThat(foreignKey)
				.containsEntry("constraint_name", "fk_projects_owner")
				.containsEntry("column_name", "owner_id")
				.containsEntry("referenced_table", "app_users")
				.containsEntry("referenced_column", "id");
	}

	@Test
	void projectMembersUsesACompositePrimaryKey() {
		List<String> primaryKeyColumns = jdbcTemplate.queryForList("""
				SELECT kcu.column_name
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				WHERE tc.constraint_type = 'PRIMARY KEY'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'project_members'
				ORDER BY kcu.ordinal_position
				""", String.class);

		assertThat(primaryKeyColumns).containsExactly("project_id", "user_id");
	}

	@Test
	void projectMembersReferencesProjectsAndAppUsers() {
		List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList("""
				SELECT
				    tc.constraint_name,
				    kcu.column_name,
				    ccu.table_name AS referenced_table,
				    ccu.column_name AS referenced_column
				FROM information_schema.table_constraints tc
				JOIN information_schema.key_column_usage kcu
				  ON tc.constraint_catalog = kcu.constraint_catalog
				 AND tc.constraint_schema = kcu.constraint_schema
				 AND tc.constraint_name = kcu.constraint_name
				JOIN information_schema.constraint_column_usage ccu
				  ON tc.constraint_catalog = ccu.constraint_catalog
				 AND tc.constraint_schema = ccu.constraint_schema
				 AND tc.constraint_name = ccu.constraint_name
				WHERE tc.constraint_type = 'FOREIGN KEY'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'project_members'
				""");

		assertThat(foreignKeys)
				.anySatisfy(foreignKey -> assertThat(foreignKey)
						.containsEntry("constraint_name", "fk_project_members_project")
						.containsEntry("column_name", "project_id")
						.containsEntry("referenced_table", "projects")
						.containsEntry("referenced_column", "id"))
				.anySatisfy(foreignKey -> assertThat(foreignKey)
						.containsEntry("constraint_name", "fk_project_members_user")
						.containsEntry("column_name", "user_id")
						.containsEntry("referenced_table", "app_users")
						.containsEntry("referenced_column", "id"));
	}

	@Test
	void projectMembersRestrictsPersistedRolesToCollaboratorRoles() {
		String checkClause = jdbcTemplate.queryForObject("""
				SELECT cc.check_clause
				FROM information_schema.table_constraints tc
				JOIN information_schema.check_constraints cc
				  ON tc.constraint_catalog = cc.constraint_catalog
				 AND tc.constraint_schema = cc.constraint_schema
				 AND tc.constraint_name = cc.constraint_name
				WHERE tc.constraint_type = 'CHECK'
				  AND tc.table_schema = 'public'
				  AND tc.table_name = 'project_members'
				  AND tc.constraint_name = 'chk_project_members_role'
				""", String.class);

		assertThat(checkClause)
				.contains("MAINTAINER", "MEMBER", "VIEWER")
				.doesNotContain("OWNER");
	}

	@Test
	void projectMembersHasAnIndexForUserMembershipLookups() {
		List<String> indexes = jdbcTemplate.queryForList("""
				SELECT indexname
				FROM pg_indexes
				WHERE schemaname = 'public'
				  AND tablename = 'project_members'
				""", String.class);

		assertThat(indexes).contains("idx_project_members_user_id");
	}

	@Test
	void registerLoginAndBearerTokenValidationWorkEndToEnd() throws Exception {
		String registrationJson = """
				{
				  "email": " Integration.User@Example.com ",
				  "password": "strong-password",
				  "displayName": "Integration User"
				}
				""";

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(3600))
				.andExpect(jsonPath("$.user.email").value("integration.user@example.com"));

		String storedHash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM app_users WHERE email = ?",
				String.class,
				"integration.user@example.com"
		);
		assertThat(storedHash)
				.startsWith("$2")
				.isNotEqualTo("strong-password");

		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(registrationJson))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email": "integration.user@example.com",
								  "password": "wrong-password"
								}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		String loginResponse = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email": " Integration.User@Example.com ",
								  "password": "strong-password"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value("integration.user@example.com"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		String accessToken = JsonPath.read(loginResponse, "$.accessToken");
		mockMvc.perform(get("/api/auth/me")
						.header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("integration.user@example.com"))
				.andExpect(jsonPath("$.displayName").value("Integration User"));

		mockMvc.perform(get("/api/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void experimentConfigVersionsAreCreatedWithoutOverwritingHistory() throws Exception {
		String registrationResponse = mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "email": "config-owner@example.com",
								  "password": "strong-password",
								  "displayName": "Config Owner"
								}
								"""))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String accessToken = JsonPath.read(registrationResponse, "$.accessToken");

		String projectResponse = mockMvc.perform(post("/api/projects")
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Versioned Config Test"}
								"""))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		Number projectId = JsonPath.read(projectResponse, "$.id");

		mockMvc.perform(post("/api/projects/{projectId}/configs", projectId.longValue())
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(configRequest("sto-3g")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("Baseline"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.spec.basis").value("sto-3g"));

		mockMvc.perform(post("/api/projects/{projectId}/configs", projectId.longValue())
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(configRequest("6-31g")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.spec.basis").value("6-31g"));

		List<Map<String, Object>> versions = jdbcTemplate.queryForList("""
				SELECT version, spec_json ->> 'basis' AS basis
				FROM experiment_configs
				WHERE project_id = ? AND name = 'Baseline'
				ORDER BY version
				""", projectId.longValue());

		assertThat(versions).hasSize(2);
		assertThat(versions.get(0)).containsEntry("version", 1).containsEntry("basis", "sto-3g");
		assertThat(versions.get(1)).containsEntry("version", 2).containsEntry("basis", "6-31g");
	}

	private String configRequest(String basis) {
		return """
				{
				  "name": "Baseline",
				  "spec": {
				    "schemaVersion": 1,
				    "taskType": "pyscf.single_point",
				    "method": "RHF",
				    "basis": "%s",
				    "charge": 0,
				    "spin": 0,
				    "maxMemoryMb": 1024,
				    "timeoutSeconds": 300
				  }
				}
				""".formatted(basis);
	}
}
