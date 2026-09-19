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

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PhoneVerificationConfigTest {

    @Test
    public void hasWorkingDefaults() {
        PhoneVerificationConfig config = PhoneVerificationConfig.of(Map.of());

        assertThat(config.codeLength(), is(6));
        assertThat(config.codeLifespanSeconds(), is(300));
        assertThat(config.maxAttempts(), is(3));
        assertThat(config.resendCooldownSeconds(), is(60));
    }

    @Test
    public void readsConfiguredValues() {
        PhoneVerificationConfig config = PhoneVerificationConfig.of(Map.of(
                PhoneVerificationConfig.CODE_LENGTH, "8",
                PhoneVerificationConfig.CODE_LIFESPAN, "120",
                PhoneVerificationConfig.MAX_ATTEMPTS, "5",
                PhoneVerificationConfig.RESEND_COOLDOWN, "30"));

        assertThat(config.codeLength(), is(8));
        assertThat(config.codeLifespanSeconds(), is(120));
        assertThat(config.maxAttempts(), is(5));
        assertThat(config.resendCooldownSeconds(), is(30));
    }

    @Test
    public void fallsBackRatherThanFailingOnNonsense() {
        // a misconfigured realm must not become an exception on a login path
        PhoneVerificationConfig config = PhoneVerificationConfig.of(Map.of(
                PhoneVerificationConfig.CODE_LENGTH, "banana",
                PhoneVerificationConfig.CODE_LIFESPAN, "",
                PhoneVerificationConfig.MAX_ATTEMPTS, "-1"));

        assertThat(config.codeLength(), is(6));
        assertThat(config.codeLifespanSeconds(), is(300));
        assertThat(config.maxAttempts(), is(1));
    }

    @Test
    public void keepsTheCodeLongEnoughToBeWorthGuessingAndShortEnoughToType() {
        assertThat(PhoneVerificationConfig.of(Map.of(PhoneVerificationConfig.CODE_LENGTH, "3")).codeLength(), is(6));
        assertThat(PhoneVerificationConfig.of(Map.of(PhoneVerificationConfig.CODE_LENGTH, "99")).codeLength(), is(12));
    }

    @Test
    public void neverAllowsAnUnboundedNumberOfGuesses() {
        assertThat(PhoneVerificationConfig.of(Map.of(PhoneVerificationConfig.MAX_ATTEMPTS, "0")).maxAttempts(), is(1));
        assertThat(PhoneVerificationConfig.of(Map.of(PhoneVerificationConfig.MAX_ATTEMPTS, "1000")).maxAttempts(), is(10));
    }

    @Test
    public void toleratesNoConfigurationAtAll() {
        assertThat(PhoneVerificationConfig.of(null).codeLength(), is(6));
    }
}
