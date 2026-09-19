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

import java.util.List;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.keycloak.services.resource.RealmResourceProvider;

/**
 * Hands back what {@link RecordingPhoneMessageSenderProviderFactory} was asked to send.
 *
 * <p>A test cannot read the sender's own record directly: the sender runs in the server, which
 * is a different process whenever the tests are not run embedded.
 */
public class RecordedPhoneMessagesResourceProvider implements RealmResourceProvider {

    @Override
    public Object getResource() {
        return this;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<RecordingPhoneMessageSenderProviderFactory.Sent> sent() {
        return RecordingPhoneMessageSenderProviderFactory.sent();
    }

    @DELETE
    public void clear() {
        RecordingPhoneMessageSenderProviderFactory.clear();
    }

    @Override
    public void close() {
    }
}
