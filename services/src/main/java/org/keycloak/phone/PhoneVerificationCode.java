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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

import org.keycloak.common.util.SecretGenerator;

/**
 * A verification code, kept as a salted hash.
 */
public final class PhoneVerificationCode {

    static final String HASH = "hash";
    static final String SALT = "salt";

    private static final String ALGORITHM = "SHA-256";
    private static final int SALT_LENGTH = 32;

    private final String salt;
    private final String hash;

    private PhoneVerificationCode(String salt, String hash) {
        this.salt = salt;
        this.hash = hash;
    }

    public static PhoneVerificationCode of(String code) {
        String salt = SecretGenerator.getInstance().randomString(SALT_LENGTH);
        return new PhoneVerificationCode(salt, hash(salt, code));
    }

    public static PhoneVerificationCode fromNotes(Map<String, String> notes) {
        if (notes == null) {
            return null;
        }
        String salt = notes.get(SALT);
        String hash = notes.get(HASH);
        if (salt == null || hash == null) {
            return null;
        }
        return new PhoneVerificationCode(salt, hash);
    }

    public boolean matches(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(hash.getBytes(StandardCharsets.UTF_8),
                hash(salt, candidate).getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, String> toNotes() {
        return Map.of(SALT, salt, HASH, hash);
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
