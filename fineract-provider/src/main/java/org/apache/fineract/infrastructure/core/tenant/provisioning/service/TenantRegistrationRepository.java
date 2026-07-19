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

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.ProvisionedTenantData;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC (not JPA) access for registering new tenants in the tenant-store database: inserting a row into
 * {@code tenant_server_connections} followed by a row into {@code tenants} that references it, and reading back
 * already-provisioned tenants. Follows the same pattern as
 * {@link org.apache.fineract.infrastructure.core.service.tenant.JdbcTenantDetailsService}.
 */
@Repository
public class TenantRegistrationRepository {

    private static final RowMapper<ProvisionedTenantData> ROW_MAPPER = (rs, rowNum) -> ProvisionedTenantData.builder()
            .id(rs.getLong("id")).identifier(rs.getString("identifier")).name(rs.getString("name"))
            .timezoneId(rs.getString("timezone_id")).build();

    private final JdbcTemplate jdbcTemplate;

    public TenantRegistrationRepository(@Qualifier("hikariTenantDataSource") final DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public boolean existsByIdentifier(final String tenantIdentifier) {
        final Integer count = jdbcTemplate.queryForObject("select count(*) from tenants where identifier = ?", Integer.class,
                tenantIdentifier);
        return count != null && count > 0;
    }

    public List<ProvisionedTenantData> findAll() {
        return jdbcTemplate.query("select id, identifier, name, timezone_id from tenants order by id", ROW_MAPPER);
    }

    public Optional<ProvisionedTenantData> findByIdentifier(final String tenantIdentifier) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "select id, identifier, name, timezone_id from tenants where identifier = ?", ROW_MAPPER, tenantIdentifier));
        } catch (final EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Registers a new tenant: first a {@code tenant_server_connections} row describing how to reach the tenant's
     * own database, then a {@code tenants} row referencing it as both the OLTP and report connection.
     *
     * @param encryptedSchemaPassword
     *            the tenant DB password, already encrypted via {@link org.apache.fineract.infrastructure.core.service.database.DatabasePasswordEncryptor}
     *            - Fineract requires this column to hold ciphertext, not plaintext, matching how every other
     *            tenant connection is stored.
     * @param masterPasswordHash
     *            the bcrypt hash of the app's configured master password, checked by
     *            {@code DatabasePasswordEncryptor.isMasterPasswordHashValid} before this connection can be used.
     */
    public void registerTenant(final String tenantIdentifier, final String tenantName, final String timezoneId, final String schemaServer,
            final String schemaServerPort, final String schemaName, final String schemaUsername, final String encryptedSchemaPassword,
            final String masterPasswordHash) {
        final Long connectionId = insertTenantServerConnection(schemaServer, schemaServerPort, schemaName, schemaUsername,
                encryptedSchemaPassword, masterPasswordHash);
        insertTenant(tenantIdentifier, tenantName, timezoneId, connectionId);
    }

    private Long insertTenantServerConnection(final String schemaServer, final String schemaServerPort, final String schemaName,
            final String schemaUsername, final String encryptedSchemaPassword, final String masterPasswordHash) {
        final String sql = "insert into tenant_server_connections "
                + "(schema_server, schema_name, schema_server_port, schema_username, schema_password, master_password_hash, auto_update) "
                + "values (?, ?, ?, ?, ?, ?, 1)";

        final KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            final PreparedStatement ps = connection.prepareStatement(sql, new String[] { "id" });
            ps.setString(1, schemaServer);
            ps.setString(2, schemaName);
            ps.setString(3, schemaServerPort);
            ps.setString(4, schemaUsername);
            ps.setString(5, encryptedSchemaPassword);
            ps.setString(6, masterPasswordHash);
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    private void insertTenant(final String tenantIdentifier, final String tenantName, final String timezoneId, final Long connectionId) {
        final String sql = "insert into tenants (identifier, name, timezone_id, oltp_id, report_id, created_date, lastmodified_date) "
                + "values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)";

        jdbcTemplate.update(sql, tenantIdentifier, tenantName, timezoneId, connectionId, connectionId);
    }
}
