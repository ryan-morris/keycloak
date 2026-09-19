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
import java.util.HashMap;
import java.util.Map;

import org.keycloak.common.util.SecretGenerator;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Issues and verifies phone verification codes, kept in the single-use object store.
 *
 * <p>The store has no atomic counter, so each attempt removes one of the entries written with the code.
 */
public class PhoneVerificationManager {

    static final String ATTEMPT_MARKER = ".attempt.";

    private static final String PREFIX = "phone.verify.";
    private static final String NUMBER = "number";
    private static final String GENERATION = "generation";
    private static final int GENERATION_LENGTH = 16;

    private final SingleUseObjectProvider store;

    public PhoneVerificationManager(SingleUseObjectProvider store) {
        this.store = store;
    }

    public enum Result {
        VERIFIED,
        INVALID,
        // never issued, expired, or already used
        EXPIRED,
        // the code has since been replaced
        STALE,
        NUMBER_CHANGED,
        // no attempts left
        EXHAUSTED
    }

    public record IssuedCode(String code, String generation) {
    }

    /**
     * Issues a code for the number, replacing any previous code of the user.
     */
    public IssuedCode issue(String userId, String number, PhoneVerificationConfig config) {
        SecretGenerator secrets = SecretGenerator.getInstance();
        String code = secrets.randomString(config.codeLength(), SecretGenerator.DIGITS);
        String generation = secrets.randomString(GENERATION_LENGTH);

        Map<String, String> notes = new HashMap<>(PhoneVerificationCode.of(code).toNotes());
        // the code is only valid for the number it was sent to
        notes.put(NUMBER, fingerprint(number));

        // the pointer is written last, so that a concurrent guess finds nothing
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            store.putIfAbsent(attemptKey(userId, generation, attempt), config.codeLifespanSeconds());
        }
        store.put(codeKey(userId, generation), config.codeLifespanSeconds(), notes);

        String replaced = currentGeneration(userId);
        store.put(currentKey(userId), config.codeLifespanSeconds(), Map.of(GENERATION, generation));
        if (replaced != null) {
            discardGeneration(userId, replaced);
        }

        return new IssuedCode(code, generation);
    }

    public Result verify(String userId, String number, String generation, String guess) {
        String current = currentGeneration(userId);
        if (current == null) {
            return Result.EXPIRED;
        }
        if (generation == null || !generation.equals(current)) {
            return Result.STALE;
        }

        Map<String, String> notes = store.get(codeKey(userId, generation));
        if (notes == null) {
            return Result.EXPIRED;
        }
        if (!fingerprint(number).equals(notes.get(NUMBER))) {
            return Result.NUMBER_CHANGED;
        }
        if (guess == null || guess.isBlank()) {
            // an empty submit does not use up an attempt
            return Result.INVALID;
        }
        if (!claimAttempt(userId, generation)) {
            return Result.EXHAUSTED;
        }

        PhoneVerificationCode code = PhoneVerificationCode.fromNotes(notes);
        if (code == null || !code.matches(guess)) {
            return Result.INVALID;
        }

        // only the request that removes the code verifies it
        return store.remove(codeKey(userId, generation)) == null ? Result.EXPIRED : Result.VERIFIED;
    }

    /**
     * Removes the code of the given generation, leaving a code issued since untouched.
     */
    public void discard(String userId, String generation) {
        if (generation == null) {
            return;
        }
        discardGeneration(userId, generation);
    }

    private void discardGeneration(String userId, String generation) {
        store.remove(codeKey(userId, generation));
        for (int attempt = 0; attempt < PhoneVerificationConfig.MAX_ATTEMPTS_CEILING; attempt++) {
            store.remove(attemptKey(userId, generation, attempt));
        }
    }

    private String currentGeneration(String userId) {
        Map<String, String> pointer = store.get(currentKey(userId));
        return pointer == null ? null : pointer.get(GENERATION);
    }

    private boolean claimAttempt(String userId, String generation) {
        for (int attempt = 0; attempt < PhoneVerificationConfig.MAX_ATTEMPTS_CEILING; attempt++) {
            if (store.remove(attemptKey(userId, generation, attempt)) != null) {
                return true;
            }
        }
        return false;
    }

    private String currentKey(String userId) {
        return PREFIX + userId;
    }

    private String codeKey(String userId, String generation) {
        return currentKey(userId) + "." + generation;
    }

    private String attemptKey(String userId, String generation, int attempt) {
        return codeKey(userId, generation) + ATTEMPT_MARKER + attempt;
    }

    // the number itself is not kept in the store
    private static String fingerprint(String number) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(number.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
