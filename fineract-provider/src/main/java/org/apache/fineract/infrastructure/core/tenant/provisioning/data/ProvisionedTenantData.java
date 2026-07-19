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
package org.apache.fineract.infrastructure.core.tenant.provisioning.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Read-only view of a provisioned tenant, as listed via {@code GET /v1/internal/tenants} and
 * {@code GET /v1/internal/tenants/{identifier}}.
 */
@Getter
@Builder
@AllArgsConstructor
public class ProvisionedTenantData {

    private Long id;
    private String identifier;
    private String name;
    private String timezoneId;
}
