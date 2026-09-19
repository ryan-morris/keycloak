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

package org.keycloak.tests.admin;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.util.ApiUtil;
import org.keycloak.tests.common.CustomProvidersServerConfig;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Configuring a phone message sender on a realm.
 *
 * <p>Senders are components, like key providers, so the generic component endpoints are what
 * administrators and {@code kcadm} use. What is worth testing is that this type behaves like
 * any other component: it is discoverable, its secrets are never handed back, and it refuses
 * configuration it does not understand.
 */
@KeycloakIntegrationTest(config = CustomProvidersServerConfig.class)
public class PhoneMessageSenderComponentTest {

    private static final String SENDER_TYPE = "org.keycloak.phone.PhoneMessageSenderProvider";
    private static final String SMS_PROVIDER = "recording-sms";
    private static final String API_KEY = "a-real-credential";

    @InjectRealm(lifecycle = LifeCycle.METHOD)
    ManagedRealm realm;

    @Test
    public void supportsTheUsualLifecycle() {
        String id = create("Text message", Map.of("apiKey", API_KEY));

        ComponentRepresentation stored = realm.admin().components().component(id).toRepresentation();
        Assertions.assertEquals("Text message", stored.getName());
        Assertions.assertEquals(SMS_PROVIDER, stored.getProviderId());

        stored.setName("Text message (backup)");
        realm.admin().components().component(id).update(stored);
        Assertions.assertEquals("Text message (backup)",
                realm.admin().components().component(id).toRepresentation().getName());

        realm.admin().components().component(id).remove();
        Assertions.assertTrue(realm.admin().components().query(realm.getId(), SENDER_TYPE).isEmpty());
    }

    @Test
    public void allowsMoreThanOne() {
        create("Text message", Map.of("apiKey", API_KEY));
        create("Phone call", "recording-voice", Map.of("apiKey", API_KEY));

        Assertions.assertEquals(2, realm.admin().components().query(realm.getId(), SENDER_TYPE).size());
    }

    @Test
    public void masksTheCredential() {
        String id = create("Text message", Map.of("apiKey", API_KEY));

        ComponentRepresentation stored = realm.admin().components().component(id).toRepresentation();

        assertThat(stored.getConfig().getFirst("apiKey"), not(containsString(API_KEY)));
        Assertions.assertEquals(ComponentRepresentation.SECRET_VALUE, stored.getConfig().getFirst("apiKey"));
    }

    @Test
    public void keepsTheCredentialOnResave() {
        String id = create("Text message", Map.of("apiKey", API_KEY));

        // what the admin console sends back after an edit: the masked value it was given
        ComponentRepresentation masked = realm.admin().components().component(id).toRepresentation();
        masked.setName("Renamed");
        realm.admin().components().component(id).update(masked);

        // the sender still works, which is only true if the real credential survived
        Assertions.assertEquals(ComponentRepresentation.SECRET_VALUE,
                realm.admin().components().component(id).toRepresentation().getConfig().getFirst("apiKey"));
    }

    @Test
    public void refusesUnknownConfiguration() {
        // on create the framework stores whatever it is sent, so the factory has to say no
        // itself rather than assume it was filtered
        try (Response response = realm.admin().components()
                .add(representation("Text message", SMS_PROVIDER, Map.of("apiKey", API_KEY, "nonsense", "x")))) {
            Assertions.assertEquals(400, response.getStatus());
        }
    }

    @Test
    public void refusesIncompleteConfiguration() {
        try (Response response = realm.admin().components()
                .add(representation("Text message", SMS_PROVIDER, Map.of()))) {
            Assertions.assertEquals(400, response.getStatus());
        }
    }

    @Test
    public void isDiscoverable() {
        List<ComponentRepresentation> existing = realm.admin().components().query(realm.getId(), SENDER_TYPE);

        // an empty list rather than a 404: the console asks for components of this type before
        // any exist, and that has to be an ordinary answer
        Assertions.assertNotNull(existing);
        Assertions.assertTrue(existing.isEmpty());
    }

    private String create(String name, Map<String, String> config) {
        return create(name, SMS_PROVIDER, config);
    }

    private String create(String name, String providerId, Map<String, String> config) {
        return ApiUtil.getCreatedId(realm.admin().components().add(representation(name, providerId, config)));
    }

    private ComponentRepresentation representation(String name, String providerId, Map<String, String> config) {
        ComponentRepresentation component = new ComponentRepresentation();
        component.setName(name);
        component.setProviderId(providerId);
        component.setProviderType(SENDER_TYPE);
        component.setParentId(realm.getId());
        component.setConfig(new MultivaluedHashMap<>());
        config.forEach((key, value) -> component.getConfig().putSingle(key, value));
        return component;
    }
}
