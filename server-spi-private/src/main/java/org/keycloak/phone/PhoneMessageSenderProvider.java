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

import org.keycloak.provider.Provider;

/**
 * Delivers a short text message to a phone number over one channel, for example SMS.
 */
public interface PhoneMessageSenderProvider extends Provider {

    /**
     * Sends {@code message} to {@code number}.
     *
     * @param number the destination, as stored on the user
     * @param message the text to deliver, already rendered and localized
     * @throws PhoneMessageException if the message could not be handed over
     */
    void send(String number, PhoneMessage message) throws PhoneMessageException;

    @Override
    default void close() {
    }
}
