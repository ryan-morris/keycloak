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

package org.keycloak.authentication.authenticators.browser;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.phone.PhoneVerificationConfig;
import org.keycloak.provider.ProviderConfigProperty;

public class PhoneOtpFormAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "auth-phone-otp-form";
    public static final String REFERENCE_CATEGORY = "phone-otp";

    public static final PhoneOtpFormAuthenticator SINGLETON = new PhoneOtpFormAuthenticator();

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getReferenceCategory() {
        return REFERENCE_CATEGORY;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public String getDisplayType() {
        return "Phone OTP Form";
    }

    @Override
    public String getHelpText() {
        return "Validates a code sent to the verified phone number of the user on a separate form.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty codeLength = new ProviderConfigProperty();
        codeLength.setName(PhoneVerificationConfig.CODE_LENGTH);
        codeLength.setLabel("Code length");
        codeLength.setHelpText("How many digits the code has. Between 6 and 12.");
        codeLength.setType(ProviderConfigProperty.INTEGER_TYPE);
        codeLength.setDefaultValue(PhoneVerificationConfig.DEFAULT_CODE_LENGTH);

        ProviderConfigProperty lifespan = new ProviderConfigProperty();
        lifespan.setName(PhoneVerificationConfig.CODE_LIFESPAN);
        lifespan.setLabel("Code lifespan");
        lifespan.setHelpText("How long, in seconds, a code can be used for.");
        lifespan.setType(ProviderConfigProperty.INTEGER_TYPE);
        lifespan.setDefaultValue(PhoneVerificationConfig.DEFAULT_CODE_LIFESPAN_SECONDS);

        ProviderConfigProperty attempts = new ProviderConfigProperty();
        attempts.setName(PhoneVerificationConfig.MAX_ATTEMPTS);
        attempts.setLabel("Maximum attempts");
        attempts.setHelpText("How many times a code can be entered incorrectly before it stops working. "
                + "Between 1 and 10.");
        attempts.setType(ProviderConfigProperty.INTEGER_TYPE);
        attempts.setDefaultValue(PhoneVerificationConfig.DEFAULT_MAX_ATTEMPTS);

        ProviderConfigProperty cooldown = new ProviderConfigProperty();
        cooldown.setName(PhoneVerificationConfig.RESEND_COOLDOWN);
        cooldown.setLabel("Resend cooldown");
        cooldown.setHelpText("How long, in seconds, before another code can be sent to the same number.");
        cooldown.setType(ProviderConfigProperty.INTEGER_TYPE);
        cooldown.setDefaultValue(PhoneVerificationConfig.DEFAULT_RESEND_COOLDOWN_SECONDS);

        return List.of(codeLength, lifespan, attempts, cooldown);
    }
}
