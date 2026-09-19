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

import org.keycloak.models.UserModel;

/**
 * The user attributes mapped by the built-in {@code phone} client scope.
 */
public final class PhoneAttributes {

    public static final String PHONE_NUMBER = "phoneNumber";

    public static final String PHONE_NUMBER_VERIFIED = "phoneNumberVerified";

    private PhoneAttributes() {
    }

    public static String phoneNumber(UserModel user) {
        return user.getFirstAttribute(PHONE_NUMBER);
    }

    public static boolean isPhoneNumberVerified(UserModel user) {
        return Boolean.parseBoolean(user.getFirstAttribute(PHONE_NUMBER_VERIFIED));
    }

    public static void setPhoneNumberVerified(UserModel user, boolean verified) {
        user.setSingleAttribute(PHONE_NUMBER_VERIFIED, Boolean.toString(verified));
    }
}
