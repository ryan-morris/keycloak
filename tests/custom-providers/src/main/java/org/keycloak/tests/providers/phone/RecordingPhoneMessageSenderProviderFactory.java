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

package org.keycloak.tests.providers.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.phone.PhoneMessage;
import org.keycloak.phone.PhoneMessageException;
import org.keycloak.phone.PhoneMessageSenderProvider;
import org.keycloak.phone.PhoneMessageSenderProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * A sender that keeps what it was asked to send instead of sending it.
 *
 * <p>Registered twice, as an SMS sender and as a voice sender, so that tests can exercise a
 * realm that offers a user more than one way of receiving a code.
 *
 * <p>It declares a secret property so that masking is exercised, and a property that makes it
 * fail, so that the "nothing was delivered" path is reachable.
 */
public abstract class RecordingPhoneMessageSenderProviderFactory implements PhoneMessageSenderProviderFactory {

    public static final String CONFIG_API_KEY = "apiKey";
    public static final String CONFIG_FAIL = "fail";
    public static final String CONFIG_FAIL_AFTER_SENDING = "failAfterSending";

    private static final List<Sent> SENT = Collections.synchronizedList(new ArrayList<>());

    /**
     * What a sender was asked to deliver.
     */
    public record Sent(String channel, String number, String text, String purpose, Locale locale, String apiKey) {
    }

    public static List<Sent> sent() {
        synchronized (SENT) {
            return List.copyOf(SENT);
        }
    }

    public static Sent lastSent() {
        synchronized (SENT) {
            return SENT.isEmpty() ? null : SENT.get(SENT.size() - 1);
        }
    }

    public static void clear() {
        SENT.clear();
    }

    @Override
    public PhoneMessageSenderProvider create(KeycloakSession session, ComponentModel model) {
        return new PhoneMessageSenderProvider() {

            @Override
            public void send(String number, PhoneMessage message) throws PhoneMessageException {
                if (Boolean.parseBoolean(model.getConfig().getFirst(CONFIG_FAIL))) {
                    throw new PhoneMessageException("This sender was configured to fail");
                }
                // the credential is recorded so that a test can tell whether what was stored
                // survived being saved again from a form that only ever saw the mask
                SENT.add(new Sent(getChannel(), number, message.text(), message.purpose(), message.locale(),
                        model.getConfig().getFirst(CONFIG_API_KEY)));

                if (Boolean.parseBoolean(model.getConfig().getFirst(CONFIG_FAIL_AFTER_SENDING))) {
                    // the message went out and the failure came afterwards, which is the case
                    // a sender cannot tell apart from never having sent at all
                    throw new PhoneMessageException("Delivered, then failed");
                }
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        if (model.getConfig().getFirst(CONFIG_API_KEY) == null) {
            throw new ComponentValidationException("An API key is required");
        }
        // anything not declared is rejected here: on create, nothing else filters it out
        for (String key : model.getConfig().keySet()) {
            if (!CONFIG_API_KEY.equals(key) && !CONFIG_FAIL.equals(key)
                    && !CONFIG_FAIL_AFTER_SENDING.equals(key)) {
                throw new ComponentValidationException("Unknown configuration property: " + key);
            }
        }
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name(CONFIG_API_KEY)
                .label("API key")
                .helpText("Credential for the service that delivers the message.")
                .type(ProviderConfigProperty.PASSWORD)
                .secret(true)
                .add()
                .property()
                .name(CONFIG_FAIL)
                .label("Fail every send")
                .helpText("Makes every send fail, to exercise the failure path.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .add()
                .property()
                .name(CONFIG_FAIL_AFTER_SENDING)
                .label("Fail after sending")
                .helpText("Delivers the message and then fails, as a sender does when it loses "
                        + "the connection after the network took the message.")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .add()
                .build();
    }

    @Override
    public String getHelpText() {
        return "Records messages instead of sending them";
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

    /**
     * Delivers by text message.
     */
    public static class Sms extends RecordingPhoneMessageSenderProviderFactory {

        public static final String PROVIDER_ID = "recording-sms";

        @Override
        public String getId() {
            return PROVIDER_ID;
        }

        @Override
        public String getChannel() {
            return "sms";
        }

        @Override
        public String getDisplayText() {
            return "Text me a code";
        }
    }

    /**
     * Delivers by phone call: a different provider, not a mode of the one above.
     */
    public static class Voice extends RecordingPhoneMessageSenderProviderFactory {

        public static final String PROVIDER_ID = "recording-voice";

        @Override
        public String getId() {
            return PROVIDER_ID;
        }

        @Override
        public String getChannel() {
            return "voice";
        }

        @Override
        public String getDisplayText() {
            return "Call me with a code";
        }
    }
}
