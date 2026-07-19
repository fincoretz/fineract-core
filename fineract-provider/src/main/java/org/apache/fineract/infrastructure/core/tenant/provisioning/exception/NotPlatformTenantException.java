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
package org.apache.fineract.infrastructure.core.tenant.provisioning.exception;

import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;

/**
 * Thrown when tenant-provisioning endpoints are called on any tenant other than the configured platform-admin
 * tenant ({@code fineract.platform.admin-tenant-id}). This is enforced in addition to (and independently of) the
 * normal {@code CREATE_TENANT}/{@code READ_TENANT} permission checks, so that even a user who somehow holds those
 * permissions cannot provision tenants unless authenticated against the dedicated platform tenant.
 */
public class NotPlatformTenantException extends NoAuthorizationException {

    public NotPlatformTenantException(final String resolvedTenantIdentifier) {
        super("Tenant provisioning is only permitted via the platform-admin tenant. Resolved tenant '"
                + resolvedTenantIdentifier + "' is not the configured platform tenant.");
    }
}
