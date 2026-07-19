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
package org.apache.fineract.infrastructure.core.tenant.provisioning.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.tenant.provisioning.data.CreateTenantRequest;
import org.apache.fineract.infrastructure.core.tenant.provisioning.exception.NotPlatformTenantException;
import org.apache.fineract.infrastructure.core.tenant.provisioning.service.TenantProvisioningService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * Internal REST API allowing a designated platform-admin to create new Fineract tenants (multi-tenant SaaS
 * onboarding), asynchronously with job-status polling.
 *
 * <p>
 * This endpoint sits behind the normal {@code TenantAwareBasicAuthenticationFilter} tenant-resolution flow: the
 * caller must send a valid {@code Fineract-Platform-TenantId} header plus Basic Auth for a user in that tenant. Two
 * checks are enforced before allowing tenant creation: (1) the authenticated user has the {@code CREATE_TENANT} /
 * {@code READ_TENANT} permission, and (2) the request's resolved tenant identifier equals the configured
 * platform-admin tenant ({@code fineract.platform.admin-tenant-id}) - rejected with 403 otherwise, even if the
 * user's role somehow has the permission.
 * </p>
 */
@Path("/v1/internal/tenants")
@Component
@Tag(name = "Tenant Provisioning", description = "Internal API for platform-admins to provision new Fineract tenants.")
@RequiredArgsConstructor
public class TenantProvisioningApiResource {

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "TENANT";

    private final PlatformSecurityContext context;
    private final FineractProperties fineractProperties;
    private final TenantProvisioningService tenantProvisioningService;
    private final FromJsonHelper fromJsonHelper;
    private final DefaultToApiJsonSerializer<Object> toApiJsonSerializer;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Create a new tenant", description = "Kicks off asynchronous provisioning of a new Fineract tenant and returns a job id for polling.")
    public Response createTenant(final String apiRequestBodyAsJson) {
        context.authenticatedUser().validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
        validateCallerIsOnPlatformTenant();

        final CreateTenantRequest request = fromJsonHelper.fromJson(apiRequestBodyAsJson, CreateTenantRequest.class);
        final Long jobId = tenantProvisioningService.initiateProvisioning(request);

        return Response.status(Response.Status.ACCEPTED).entity(toApiJsonSerializer.serialize(new CreateTenantResponse(jobId))).build();
    }

    @GET
    @Path("jobs/{jobId}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve a tenant-provisioning job's status")
    public String retrieveJob(@PathParam("jobId") final Long jobId) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        validateCallerIsOnPlatformTenant();

        return toApiJsonSerializer.serialize(tenantProvisioningService.retrieveJob(jobId));
    }

    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "List provisioned tenants")
    public String retrieveAllTenants() {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        validateCallerIsOnPlatformTenant();

        return toApiJsonSerializer.serialize(tenantProvisioningService.retrieveAllTenants());
    }

    @GET
    @Path("{identifier}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve a provisioned tenant's detail")
    public String retrieveTenant(@PathParam("identifier") final String identifier) {
        context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        validateCallerIsOnPlatformTenant();

        return toApiJsonSerializer.serialize(tenantProvisioningService.retrieveTenant(identifier));
    }

    /**
     * Enforced independently of (and in addition to) the normal {@code CREATE_TENANT}/{@code READ_TENANT}
     * permission checks, so that even a user who somehow holds those permissions cannot provision or inspect
     * tenants unless authenticated against the dedicated platform-admin tenant.
     */
    private void validateCallerIsOnPlatformTenant() {
        final String resolvedTenantIdentifier = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
        final String platformTenantIdentifier = fineractProperties.getPlatform().getAdminTenantId();
        if (!resolvedTenantIdentifier.equals(platformTenantIdentifier)) {
            throw new NotPlatformTenantException(resolvedTenantIdentifier);
        }
    }

    private record CreateTenantResponse(Long jobId) {}
}
