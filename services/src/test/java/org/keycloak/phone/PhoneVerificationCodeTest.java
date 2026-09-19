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

import java.util.Map;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class PhoneVerificationCodeTest {

    @Test
    public void matchesTheCodeItWasCreatedFrom() {
        PhoneVerificationCode code = PhoneVerificationCode.of("123456");

        assertThat(code.matches("123456"), is(true));
    }

    @Test
    public void rejectsAnyOtherCode() {
        PhoneVerificationCode code = PhoneVerificationCode.of("123456");

        assertThat(code.matches("123457"), is(false));
        assertThat(code.matches("12345"), is(false));
        assertThat(code.matches(""), is(false));
        assertThat(code.matches(null), is(false));
    }

    @Test
    public void neverStoresTheCodeItself() {
        PhoneVerificationCode code = PhoneVerificationCode.of("123456");

        assertThat(code.toNotes().values().stream().anyMatch(v -> v.contains("123456")), is(false));
    }

    @Test
    public void usesADifferentSaltEachTime() {
        // otherwise a stolen store lets every code be compared against one precomputed table
        PhoneVerificationCode first = PhoneVerificationCode.of("123456");
        PhoneVerificationCode second = PhoneVerificationCode.of("123456");

        assertThat(first.toNotes().get(PhoneVerificationCode.HASH),
                is(not(second.toNotes().get(PhoneVerificationCode.HASH))));
    }

    @Test
    public void survivesBeingStoredAndReadBack() {
        Map<String, String> notes = PhoneVerificationCode.of("123456").toNotes();

        PhoneVerificationCode readBack = PhoneVerificationCode.fromNotes(notes);

        assertThat(readBack, notNullValue());
        assertThat(readBack.matches("123456"), is(true));
        assertThat(readBack.matches("654321"), is(false));
    }

    @Test
    public void toleratesNotesThatAreNotThere() {
        // an expired or already consumed single-use object reads back as nothing at all
        assertThat(PhoneVerificationCode.fromNotes(null), is(nullValue()));
        assertThat(PhoneVerificationCode.fromNotes(Map.of()), is(nullValue()));
        assertThat(PhoneVerificationCode.fromNotes(Map.of(PhoneVerificationCode.HASH, "nonsense")), is(nullValue()));
    }
}
