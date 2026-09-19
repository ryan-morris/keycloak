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

import java.util.Locale;
import java.util.Objects;

/**
 * A message to deliver, already rendered.
 *
 * @param text what to send
 * @param purpose why it is being sent, see {@link #PURPOSE_VERIFY_PHONE_NUMBER}
 * @param locale the locale the text was rendered in
 */
public record PhoneMessage(String text, String purpose, Locale locale) {

    public static final String PURPOSE_VERIFY_PHONE_NUMBER = "verify-phone-number";

    public PhoneMessage {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(locale, "locale");
    }
}
