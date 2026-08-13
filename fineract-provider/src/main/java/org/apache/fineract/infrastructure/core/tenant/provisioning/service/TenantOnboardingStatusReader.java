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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.TenantOnboardingStatusData;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.TenantOnboardingStatusData.Step;
import org.springframework.stereotype.Component;

/**
 * Reads a tenant's onboarding checklist state directly from that tenant's own database, using the same
 * privileged provisioning superuser connection {@link TenantProvisioningAsyncWorker} uses - the platform
 * operator is authenticated only against the platform-admin tenant and cannot log in to individual tenants,
 * so this is server-side, read-only, and never mutates anything.
 *
 * <p>
 * Everything here is a plain {@code SELECT}; the only value interpolated into a connection URL is the schema
 * name, which is read from the tenant registry (not user input).
 * </p>
 */
@Component
@RequiredArgsConstructor
public class TenantOnboardingStatusReader {

    // Fineract's SMS/messaging gateway is the external service named MESSAGE_GATEWAY
    // (== ExternalServicesConstants.SMS_SERVICE_NAME); its seed is the localhost:9191 stub.
    private static final String SMS_GATEWAY_SERVICE = "MESSAGE_GATEWAY";
    private static final String SMS_STUB_HOST = "localhost";

    private final FineractProperties fineractProperties;

    public TenantOnboardingStatusData read(final String identifier, final String schemaName) {
        final FineractProperties.FineractTenantProvisioningProperties provisioning = fineractProperties.getTenantProvisioning();
        final String jdbcUrl = replaceDatabaseName(provisioning.getSuperuserJdbcUrl(), schemaName);

        try (Connection connection = DriverManager.getConnection(jdbcUrl, provisioning.getSuperuserUsername(),
                provisioning.getSuperuserPassword())) {
            final List<Step> steps = new ArrayList<>();
            steps.add(businessDateStep(connection));
            steps.add(externalEventsStep(connection));
            steps.add(smsGatewayStep(connection));
            steps.add(adminPasswordStep(connection));

            final boolean complete = steps.stream().allMatch(Step::isDone);
            return TenantOnboardingStatusData.builder().identifier(identifier).complete(complete).steps(steps).build();
        } catch (final SQLException e) {
            throw new TenantOnboardingStatusException(identifier, e);
        }
    }

    private Step businessDateStep(final Connection connection) throws SQLException {
        final String sql = """
                select coalesce((select enabled from c_configuration where name = 'enable-business-date'), false) as enabled,
                       (select date::text from m_business_date where type = 'BUSINESS_DATE') as business_date
                """;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            final boolean enabled = rs.getBoolean("enabled");
            final String businessDate = rs.getString("business_date");
            final boolean done = enabled && StringUtils.isNotBlank(businessDate);
            final String detail;
            if (done) {
                detail = "Business date " + businessDate;
            } else if (!enabled) {
                detail = "'enable-business-date' is off";
            } else {
                detail = "Enabled, but no business date set";
            }
            return Step.builder().key("business-date").label("Business date").done(done).detail(detail).build();
        }
    }

    private Step externalEventsStep(final Connection connection) throws SQLException {
        final String sql = "select count(*) filter (where enabled) as enabled_count, count(*) as total "
                + "from m_external_event_configuration";
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            final int enabledCount = rs.getInt("enabled_count");
            final int total = rs.getInt("total");
            final boolean done = enabledCount > 0;
            final String detail = done ? enabledCount + " of " + total + " event types enabled"
                    : "No external-event types enabled";
            return Step.builder().key("external-events").label("External event types").done(done).detail(detail).build();
        }
    }

    private Step smsGatewayStep(final Connection connection) throws SQLException {
        final String sql = """
                select
                  (select value from c_external_service_properties p join c_external_service s on s.id = p.external_service_id
                     where s.name = '%s' and p.name = 'host_name') as host,
                  (select value from c_external_service_properties p join c_external_service s on s.id = p.external_service_id
                     where s.name = '%s' and p.name = 'tenant_app_key') as app_key
                """.formatted(SMS_GATEWAY_SERVICE, SMS_GATEWAY_SERVICE);
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            final String host = rs.getString("host");
            final String appKey = rs.getString("app_key");
            final boolean done = StringUtils.isNotBlank(host) && !SMS_STUB_HOST.equalsIgnoreCase(host) && StringUtils.isNotBlank(appKey);
            final String detail = done ? "Gateway " + host : "Using the localhost:9191 stub";
            return Step.builder().key("sms-gateway").label("SMS gateway").done(done).detail(detail).build();
        }
    }

    private Step adminPasswordStep(final Connection connection) throws SQLException {
        final String sql = "select count(*) as pending from m_appuser where firsttime_login_remaining = true and is_deleted = false";
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            final int pending = rs.getInt("pending");
            final boolean done = pending == 0;
            final String detail = done ? "Seeded admin password changed" : pending + " user(s) awaiting first-login password change";
            return Step.builder().key("admin-password").label("Admin first-login").done(done).detail(detail).build();
        }
    }

    /** Swap the database segment of the superuser JDBC URL, same helper shape as the async worker. */
    private static String replaceDatabaseName(final String jdbcUrl, final String newDatabaseName) {
        final int lastSlash = jdbcUrl.lastIndexOf('/');
        return jdbcUrl.substring(0, lastSlash + 1) + newDatabaseName;
    }

    /** Unchecked so it surfaces as a 500 (the platform DB being unreachable is a server fault, not a client error). */
    static final class TenantOnboardingStatusException extends RuntimeException {

        TenantOnboardingStatusException(final String identifier, final Throwable cause) {
            super("Failed to read onboarding status for tenant '" + identifier + "'", cause);
        }
    }
}
