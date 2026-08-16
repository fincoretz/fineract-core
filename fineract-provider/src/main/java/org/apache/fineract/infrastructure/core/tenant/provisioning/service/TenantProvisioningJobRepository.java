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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.TenantProvisioningJobData;
import org.apache.fineract.infrastructure.core.tenant.provisioning.exception.TenantProvisioningJobNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC (not JPA) access to {@code m_tenant_provisioning_job} in the tenant-store database, following the
 * same pattern as {@link org.apache.fineract.infrastructure.core.service.tenant.JdbcTenantDetailsService}.
 */
@Repository
public class TenantProvisioningJobRepository {

    private static final RowMapper<TenantProvisioningJobData> ROW_MAPPER = new TenantProvisioningJobMapper();

    private static final String SELECT_BASE = "select id, tenant_identifier, tenant_name, timezone_id, status, current_step, "
            + "error_message, created_date, completed_date from m_tenant_provisioning_job";

    private final JdbcTemplate jdbcTemplate;

    public TenantProvisioningJobRepository(@Qualifier("hikariTenantDataSource") final DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public Long create(final String tenantIdentifier, final String tenantName, final String timezoneId) {
        final String sql = "insert into m_tenant_provisioning_job (tenant_identifier, tenant_name, timezone_id, status) "
                + "values (?, ?, ?, ?)";

        final KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            final PreparedStatement ps = connection.prepareStatement(sql, new String[] { "id" });
            ps.setString(1, tenantIdentifier);
            ps.setString(2, tenantName);
            ps.setString(3, timezoneId);
            ps.setString(4, TenantProvisioningStatus.PENDING.name());
            return ps;
        }, keyHolder);

        return keyHolder.getKey().longValue();
    }

    public void updateStatus(final Long jobId, final TenantProvisioningStatus status, final String currentStep) {
        jdbcTemplate.update("update m_tenant_provisioning_job set status = ?, current_step = ? where id = ?", status.name(), currentStep,
                jobId);
    }

    public void markCompleted(final Long jobId) {
        jdbcTemplate.update(
                "update m_tenant_provisioning_job set status = ?, current_step = null, completed_date = current_timestamp where id = ?",
                TenantProvisioningStatus.COMPLETED.name(), jobId);
    }

    public void markFailed(final Long jobId, final String errorMessage) {
        jdbcTemplate.update(
                "update m_tenant_provisioning_job set status = ?, error_message = ?, completed_date = current_timestamp where id = ?",
                TenantProvisioningStatus.FAILED.name(), errorMessage, jobId);
    }

    public TenantProvisioningJobData findById(final Long jobId) {
        try {
            return jdbcTemplate.queryForObject(SELECT_BASE + " where id = ?", ROW_MAPPER, jobId);
        } catch (final EmptyResultDataAccessException e) {
            throw new TenantProvisioningJobNotFoundException(jobId);
        }
    }

    private static final class TenantProvisioningJobMapper implements RowMapper<TenantProvisioningJobData> {

        @Override
        public TenantProvisioningJobData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Timestamp completedDate = rs.getTimestamp("completed_date");
            return TenantProvisioningJobData.builder().id(rs.getLong("id")).tenantIdentifier(rs.getString("tenant_identifier"))
                    .tenantName(rs.getString("tenant_name")).timezoneId(rs.getString("timezone_id")).status(rs.getString("status"))
                    .currentStep(rs.getString("current_step")).errorMessage(rs.getString("error_message"))
                    .createdDate(rs.getTimestamp("created_date").toLocalDateTime())
                    .completedDate(completedDate == null ? null : completedDate.toLocalDateTime()).build();
        }
    }
}
