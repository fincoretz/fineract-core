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

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Read-only onboarding checklist for a provisioned tenant, served by
 * {@code GET /v1/internal/tenants/{identifier}/onboarding}.
 *
 * <p>
 * A freshly provisioned tenant is technically live but not yet operational: its business date is unset,
 * no external-event types are enabled, the SMS gateway still points at the {@code localhost:9191} stub, and
 * the seeded admin is still on the first-login password. None of these fail loudly - they silently do
 * nothing - so this surfaces each as an explicit step for the platform console. It is intentionally
 * read-only; performing the steps stays on Fineract's authenticated per-tenant command APIs.
 * </p>
 */
@Getter
@Builder
@AllArgsConstructor
public class TenantOnboardingStatusData {

    private String identifier;
    /** True only when every step below is done. */
    private boolean complete;
    private List<Step> steps;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class Step {

        /** Stable machine key, e.g. {@code "business-date"}. */
        private String key;
        /** Human-readable step name. */
        private String label;
        private boolean done;
        /** Human-readable current state, e.g. {@code "Business date 2026-08-13"} or {@code "Using localhost:9191 stub"}. */
        private String detail;
    }
}
