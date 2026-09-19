/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.phone;

import java.util.Map;

/**
 * The configuration of the required action, falling back to the default for any unusable value.
 */
public record PhoneVerificationConfig(
        int codeLength,
        int codeLifespanSeconds,
        int maxAttempts,
        int resendCooldownSeconds) {

    public static final String CODE_LENGTH = "codeLength";
    public static final String CODE_LIFESPAN = "codeLifespanSeconds";
    public static final String MAX_ATTEMPTS = "maxAttempts";
    public static final String RESEND_COOLDOWN = "resendCooldownSeconds";

    public static final int DEFAULT_CODE_LENGTH = 6;
    public static final int DEFAULT_CODE_LIFESPAN_SECONDS = 300;
    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final int DEFAULT_RESEND_COOLDOWN_SECONDS = 60;

    // also how many attempt entries PhoneVerificationManager removes with a code
    public static final int MAX_ATTEMPTS_CEILING = 10;

    public static PhoneVerificationConfig of(Map<String, String> config) {
        Map<String, String> values = config == null ? Map.of() : config;

        return new PhoneVerificationConfig(
                clamp(intValue(values, CODE_LENGTH, DEFAULT_CODE_LENGTH), 6, 12),
                Math.max(30, intValue(values, CODE_LIFESPAN, DEFAULT_CODE_LIFESPAN_SECONDS)),
                clamp(intValue(values, MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS), 1, MAX_ATTEMPTS_CEILING),
                Math.max(0, intValue(values, RESEND_COOLDOWN, DEFAULT_RESEND_COOLDOWN_SECONDS)));
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
