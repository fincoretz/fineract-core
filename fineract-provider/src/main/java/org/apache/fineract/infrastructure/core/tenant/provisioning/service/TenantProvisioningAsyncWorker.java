/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.core.tenant.provisioning.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor;
import org.apache.fineract.infrastructure.core.service.migration.TenantDatabaseUpgradeService;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.CreateTenantRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Performs the actual, multi-step work of provisioning a new Fineract tenant, asynchronously:
 *
 * <ol>
 * <li>{@code CREATE DATABASE fineract_<tenant_id>} in Postgres, using a privileged (CREATEDB) role, outside any
 * transaction.</li>
 * <li>Sets up the {@code pgbouncer_auth} role/function in the new database.</li>
 * <li>Registers the tenant in the tenant-store database ({@code tenant_server_connections} + {@code tenants}),
 * with the schema password encrypted and the master-password hash set exactly as every other tenant connection
 * requires (see {@link DatabasePasswordEncryptor}).</li>
 * <li>Runs the tenant-schema Liquibase migration for just this tenant via
 * {@link TenantDatabaseUpgradeService#upgradeTenant}, which seeds the tenant's default data (including its admin
 * user) - the same migration that would otherwise only run automatically at the next full application restart.</li>
 * </ol>
 *
 * <p>
 * Note on {@code ThreadLocalContextUtil}: unlike {@code AsyncCommonCOBExecutorService}, this worker does not
 * manage the ThreadLocal tenant context itself - {@code TenantDatabaseUpgradeService#upgradeTenant} already sets
 * and resets it internally for the duration of the migration it runs. All other steps here (database creation,
 * tenant-store reads/writes) go through their own dedicated connections/datasources and never read the ThreadLocal
 * tenant context.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantProvisioningAsyncWorker {

    private static final String STEP_CREATE_DATABASE = "CREATE_DATABASE";
    private static final String STEP_SETUP_PGBOUNCER_AUTH = "SETUP_PGBOUNCER_AUTH";
    private static final String STEP_REGISTER_TENANT = "REGISTER_TENANT";
    private static final String STEP_RUN_LIQUIBASE = "RUN_LIQUIBASE";

    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    private final FineractProperties fineractProperties;
    private final TenantProvisioningJobRepository jobRepository;
    private final TenantRegistrationRepository tenantRegistrationRepository;
    private final DatabasePasswordEncryptor passwordEncryptor;
    private final TenantDatabaseUpgradeService tenantDatabaseUpgradeService;

    @Async(TaskExecutorConstant.TENANT_PROVISIONING_TASK_EXECUTOR_BEAN_NAME)
    public void provisionAsync(final Long jobId, final CreateTenantRequest request) {
        final String tenantIdentifier = request.getTenantIdentifier();
        final String databaseName = "fineract_" + tenantIdentifier;
        try {
            jobRepository.updateStatus(jobId, TenantProvisioningStatus.IN_PROGRESS, STEP_CREATE_DATABASE);
            createDatabase(databaseName);

            jobRepository.updateStatus(jobId, TenantProvisioningStatus.IN_PROGRESS, STEP_SETUP_PGBOUNCER_AUTH);
            setupPgBouncerAuth(databaseName);

            jobRepository.updateStatus(jobId, TenantProvisioningStatus.IN_PROGRESS, STEP_REGISTER_TENANT);
            registerTenant(request, databaseName);

            jobRepository.updateStatus(jobId, TenantProvisioningStatus.IN_PROGRESS, STEP_RUN_LIQUIBASE);
            tenantDatabaseUpgradeService.upgradeTenant(tenantIdentifier);

            jobRepository.markCompleted(jobId);
            log.info("Tenant '{}' provisioned successfully (job {})", tenantIdentifier, jobId);
        } catch (Exception e) {
            log.error("Tenant provisioning failed for '{}' (job {})", tenantIdentifier, jobId, e);
            final String message = e.getMessage() == null ? e.toString() : e.getMessage();
            jobRepository.markFailed(jobId, StringUtils.left(message, MAX_ERROR_MESSAGE_LENGTH));
        }
    }

    /**
     * {@code CREATE DATABASE} cannot run inside a transaction, so this uses a direct JDBC {@link Connection} in
     * autocommit mode (the default for a freshly opened connection) against the Postgres maintenance database
     * referenced by {@code fineract.tenant-provisioning.superuser-jdbc-url}, authenticating as a privileged
     * (CREATEDB) role distinct from the app's normal tenant-store user.
     */
    private void createDatabase(final String databaseName) throws SQLException {
        final FineractProperties.FineractTenantProvisioningProperties provisioning = fineractProperties.getTenantProvisioning();
        try (Connection connection = DriverManager.getConnection(provisioning.getSuperuserJdbcUrl(), provisioning.getSuperuserUsername(),
                provisioning.getSuperuserPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(databaseName) + " OWNER "
                    + quoteIdentifier(fineractProperties.getTenant().getUsername()));
        }
    }

    /**
     * Sets up the {@code pgbouncer_auth} role/function in the newly created tenant database, following the exact
     * SQL pattern in {@code config/docker/pgbouncer/setup-auth.sql}, parameterized with the configured password.
     */
    private void setupPgBouncerAuth(final String databaseName) throws SQLException {
        final FineractProperties.FineractTenantProvisioningProperties provisioning = fineractProperties.getTenantProvisioning();
        final String jdbcUrlForNewDatabase = replaceDatabaseName(provisioning.getSuperuserJdbcUrl(), databaseName);

        final String sql = """
                CREATE OR REPLACE FUNCTION pgbouncer_auth(IN p_usename TEXT, OUT usename TEXT, OUT passwd TEXT)
                RETURNS record
                LANGUAGE sql SECURITY DEFINER
                SET search_path = pg_catalog
                AS $$
                    SELECT usename, passwd FROM pg_shadow WHERE usename = p_usename;
                $$;

                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pgbouncer_auth') THEN
                        CREATE ROLE pgbouncer_auth WITH LOGIN PASSWORD '%s';
                    END IF;
                END
                $$;

                REVOKE ALL ON FUNCTION pgbouncer_auth(TEXT) FROM PUBLIC;
                GRANT EXECUTE ON FUNCTION pgbouncer_auth(TEXT) TO pgbouncer_auth;
                """.formatted(provisioning.getPgbouncerAuthPassword().replace("'", "''"));

        try (Connection connection = DriverManager.getConnection(jdbcUrlForNewDatabase, provisioning.getSuperuserUsername(),
                provisioning.getSuperuserPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Registers the tenant with its schema password encrypted (not stored as plaintext) and the current
     * master-password hash set, matching what {@code DatabasePasswordEncryptor} requires to later decrypt it -
     * see {@link DatabasePasswordEncryptor#encrypt} and {@link DatabasePasswordEncryptor#getMasterPasswordHash}.
     */
    private void registerTenant(final CreateTenantRequest request, final String databaseName) {
        final FineractProperties.FineractTenantProperties tenant = fineractProperties.getTenant();
        final String encryptedSchemaPassword = passwordEncryptor.encrypt(tenant.getPassword());
        final String masterPasswordHash = passwordEncryptor.getMasterPasswordHash();
        tenantRegistrationRepository.registerTenant(request.getTenantIdentifier(), request.getTenantName(), request.getTimezoneId(),
                tenant.getHost(), String.valueOf(tenant.getPort()), databaseName, tenant.getUsername(), encryptedSchemaPassword,
                masterPasswordHash);
    }

    private static String replaceDatabaseName(final String jdbcUrl, final String newDatabaseName) {
        final int lastSlash = jdbcUrl.lastIndexOf('/');
        return jdbcUrl.substring(0, lastSlash + 1) + newDatabaseName;
    }

    private static String quoteIdentifier(final String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
