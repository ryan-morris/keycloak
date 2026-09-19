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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * The phone message senders a realm has configured as components.
 */
public final class PhoneMessageSenders {

    private PhoneMessageSenders() {
    }

    /**
     * The senders configured in the realm, skipping those whose provider is not deployed.
     */
    public static List<ConfiguredSender> configured(KeycloakSession session, RealmModel realm) {
        return realm.getComponentsStream(realm.getId(), PhoneMessageSenderProvider.class.getName())
                .map(model -> resolve(session, model))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static ConfiguredSender resolve(KeycloakSession session, ComponentModel model) {
        PhoneMessageSenderProviderFactory factory = factory(session, model);
        return factory == null ? null : new ConfiguredSender(model, factory);
    }

    private static PhoneMessageSenderProviderFactory factory(KeycloakSession session, ComponentModel model) {
        return (PhoneMessageSenderProviderFactory) session.getKeycloakSessionFactory()
                .getProviderFactory(PhoneMessageSenderProvider.class, model.getProviderId());
    }

    public record ConfiguredSender(ComponentModel model, PhoneMessageSenderProviderFactory factory) {

        public String id() {
            return model.getId();
        }

        public String displayName() {
            String name = model.getName();
            return name == null || name.isBlank() ? factory.getDisplayText() : name;
        }

        public String channel() {
            return factory.getChannel();
        }

        public PhoneMessageSenderProvider create(KeycloakSession session) {
            return factory.create(session, model);
        }
    }
}
