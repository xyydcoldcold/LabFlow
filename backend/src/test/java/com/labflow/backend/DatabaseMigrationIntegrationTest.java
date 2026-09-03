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

		assertThat(appliedVersions).contains("1", "2", "3");
	}

	@Test
	void flywayMigrationsCreateExpectedDomainTables() {
		List<String> tables = jdbcTemplate.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN ('app_users', 'project_members', 'projects')
				ORDER BY table_name
				""", String.class);

		assertThat(tables).containsExactly("app_users", "project_members", "projects");
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
}
