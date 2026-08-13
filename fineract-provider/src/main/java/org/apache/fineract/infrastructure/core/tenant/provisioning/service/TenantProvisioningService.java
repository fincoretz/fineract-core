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

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.CreateTenantRequest;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.ProvisionedTenantData;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.TenantOnboardingStatusData;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.TenantProvisioningJobData;
import org.apache.fineract.infrastructure.core.tenant.provisioning.exception.TenantAlreadyExistsException;
import org.apache.fineract.infrastructure.core.tenant.provisioning.exception.TenantNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Entry point for the tenant-provisioning API: validates requests, creates the job row synchronously (so the
 * caller gets a job id back immediately), and delegates the actual multi-step provisioning work to
 * {@link TenantProvisioningAsyncWorker}. Also serves the read-only job-status and tenant-listing endpoints.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisioningService {

    private final TenantProvisioningJobRepository jobRepository;
    private final TenantRegistrationRepository tenantRegistrationRepository;
    private final TenantProvisioningAsyncWorker asyncWorker;
    private final TenantOnboardingStatusReader onboardingStatusReader;

    public TenantProvisioningJobData retrieveJob(final Long jobId) {
        return jobRepository.findById(jobId);
    }

    public List<ProvisionedTenantData> retrieveAllTenants() {
        return tenantRegistrationRepository.findAll();
    }

    public ProvisionedTenantData retrieveTenant(final String tenantIdentifier) {
        return tenantRegistrationRepository.findByIdentifier(tenantIdentifier)
                .orElseThrow(() -> new TenantNotFoundException(tenantIdentifier));
    }

    /**
     * Read-only onboarding checklist for a tenant, read directly from that tenant's database. The tenant must
     * already be registered (its schema name comes from the registry).
     */
    public TenantOnboardingStatusData retrieveOnboardingStatus(final String tenantIdentifier) {
        final String schemaName = tenantRegistrationRepository.findSchemaNameByIdentifier(tenantIdentifier)
                .orElseThrow(() -> new TenantNotFoundException(tenantIdentifier));
        return onboardingStatusReader.read(tenantIdentifier, schemaName);
    }

    /**
     * Validates the request and creates the job row synchronously, then kicks off the actual provisioning work
     * asynchronously.
     *
     * @return the id of the created {@code m_tenant_provisioning_job} row
     */
    public Long initiateProvisioning(final CreateTenantRequest request) {
        validate(request);
        if (tenantRegistrationRepository.existsByIdentifier(request.getTenantIdentifier())) {
            throw new TenantAlreadyExistsException(request.getTenantIdentifier());
        }

        final Long jobId = jobRepository.create(request.getTenantIdentifier(), request.getTenantName(), request.getTimezoneId());
        // Delegated to a separate bean: @Async only takes effect on calls that go through the Spring AOP proxy,
        // which a same-class (this.) call would bypass.
        asyncWorker.provisionAsync(jobId, request);
        return jobId;
    }

    private void validate(final CreateTenantRequest request) {
        final List<ApiParameterError> errors = new ArrayList<>();
        if (StringUtils.isBlank(request.getTenantIdentifier())) {
            errors.add(ApiParameterError.parameterError("error.msg.tenant.identifier.cannot.be.blank",
                    "tenantIdentifier cannot be blank", "tenantIdentifier"));
        } else if (!request.getTenantIdentifier().matches("^[a-z][a-z0-9_]{2,49}$")) {
            errors.add(ApiParameterError.parameterError("error.msg.tenant.identifier.invalid",
                    "tenantIdentifier must be lowercase alphanumeric/underscore, starting with a letter (3-50 chars)",
                    "tenantIdentifier"));
        }
        if (StringUtils.isBlank(request.getTenantName())) {
            errors.add(
                    ApiParameterError.parameterError("error.msg.tenant.name.cannot.be.blank", "tenantName cannot be blank", "tenantName"));
        }
        if (StringUtils.isBlank(request.getTimezoneId())) {
            errors.add(ApiParameterError.parameterError("error.msg.tenant.timezone.cannot.be.blank", "timezoneId cannot be blank",
                    "timezoneId"));
        }
        if (!errors.isEmpty()) {
            throw new PlatformApiDataValidationException(errors);
        }
    }
}
