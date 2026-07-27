package com.labflow.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = BackendApplication.class)
class DatabaseMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

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

		assertThat(appliedVersions).contains("1", "2");
	}

	@Test
	void flywayMigrationsCreateAppUsersAndProjectsTables() {
		List<String> tables = jdbcTemplate.queryForList("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN ('app_users', 'projects')
				ORDER BY table_name
				""", String.class);

		assertThat(tables).containsExactly("app_users", "projects");
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
}
