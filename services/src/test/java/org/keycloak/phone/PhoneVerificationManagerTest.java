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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class PhoneVerificationManagerTest {

    private static final String USER = "user-id";
    private static final String NUMBER = "+15555550123";

    private final FakeSingleUseObjects store = new FakeSingleUseObjects();
    private final PhoneVerificationManager verifications = new PhoneVerificationManager(store);

    @Test
    public void verifiesTheCodeItIssued() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void issuesACodeOfTheConfiguredLength() {
        assertThat(verifications.issue(USER, NUMBER, config(3)).code().length(), is(6));
        assertThat(verifications.issue(USER, NUMBER, new PhoneVerificationConfig(8, 300, 3, 60)).code().length(), is(8));
    }

    @Test
    public void issuesDigitsOnly() {
        // it has to be typed on a phone keypad
        assertThat(verifications.issue(USER, NUMBER, config(3)).code().matches("[0-9]+"), is(true));
    }

    @Test
    public void refusesAWrongCode() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), wrong(code)), is(PhoneVerificationManager.Result.INVALID));
    }

    @Test
    public void refusesACodeThatWasNeverIssued() {
        assertThat(verifications.verify(USER, NUMBER, "any-generation", "123456"), is(PhoneVerificationManager.Result.EXPIRED));
    }

    @Test
    public void cannotBeGuessedMoreThanTheConfiguredNumberOfTimes() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), wrong(code)), is(PhoneVerificationManager.Result.INVALID));
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), wrong(code)), is(PhoneVerificationManager.Result.INVALID));
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), wrong(code)), is(PhoneVerificationManager.Result.INVALID));

        // even the right code is refused once the guesses are used up
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.EXHAUSTED));
    }

    @Test
    public void boundsGuessesEvenWhenTheyArriveAtOnce() throws Exception {
        // the point of claiming a token per attempt: a counter that is read and written
        // separately can be beaten by firing requests in parallel, which is how this would
        // actually be attacked
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();
        String wrong = wrong(code);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<PhoneVerificationManager.Result>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                results.add(pool.submit(() -> verifications.verify(USER, NUMBER, issued.generation(), wrong)));
            }
            long compared = 0;
            for (Future<PhoneVerificationManager.Result> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == PhoneVerificationManager.Result.INVALID) {
                    compared++;
                }
            }
            assertThat(compared, is(3L));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void onlyOneOfManySimultaneousCorrectGuessesWins() throws Exception {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(5));
        String code = issued.code();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<PhoneVerificationManager.Result>> results = IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> verifications.verify(USER, NUMBER, issued.generation(), code)))
                    .collect(Collectors.toList());
            long verified = 0;
            for (Future<PhoneVerificationManager.Result> result : results) {
                if (result.get(10, TimeUnit.SECONDS) == PhoneVerificationManager.Result.VERIFIED) {
                    verified++;
                }
            }
            assertThat(verified, is(1L));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void refusesACodeIssuedForADifferentNumber() {
        // the number changed after the message went out; accepting it would assert something
        // nobody proved
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify(USER, "+15555559999", issued.generation(), code), is(PhoneVerificationManager.Result.NUMBER_CHANGED));
    }

    @Test
    public void refusesACodeIssuedForADifferentUser() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify("someone-else", NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.EXPIRED));
    }

    @Test
    public void cannotBeVerifiedTwice() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.VERIFIED));
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.EXPIRED));
    }

    @Test
    public void refusesACodeFromBeforeAResendWithoutDestroyingTheNewOne() {
        // the dangerous interleaving: a stale form is submitted while a fresh code is being
        // issued. The old code must not verify, and must not take the new code down with it.
        PhoneVerificationManager.IssuedCode first = verifications.issue(USER, NUMBER, config(3));
        PhoneVerificationManager.IssuedCode second = verifications.issue(USER, NUMBER, config(3));

        assertThat(verifications.verify(USER, NUMBER, first.generation(), first.code()),
                is(PhoneVerificationManager.Result.STALE));
        assertThat(verifications.verify(USER, NUMBER, second.generation(), second.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void answeringAnOldCodeNeverDestroysTheCodeThatReplacedIt() {
        // The interleaving that matters: the answer to the old code has spent its attempt and
        // is about to remove the record when a resend lands. Whatever it removes must not be
        // the code the user is now holding.
        PhoneVerificationManager.IssuedCode first = verifications.issue(USER, NUMBER, config(3));
        AtomicReference<PhoneVerificationManager.IssuedCode> resent = new AtomicReference<>();

        store.afterAttemptClaim(() -> resent.set(verifications.issue(USER, NUMBER, config(3))));
        verifications.verify(USER, NUMBER, first.generation(), first.code());

        PhoneVerificationManager.IssuedCode current = resent.get();
        assertThat(current, is(notNullValue()));
        assertThat(verifications.verify(USER, NUMBER, current.generation(), current.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void discardingOneGenerationLeavesAnotherAlone() {
        // a prompt abandoned in one session must not take away a code issued in another
        PhoneVerificationManager.IssuedCode first = verifications.issue(USER, NUMBER, config(3));
        PhoneVerificationManager.IssuedCode second = verifications.issue(USER, NUMBER, config(3));

        verifications.discard(USER, first.generation());

        assertThat(verifications.verify(USER, NUMBER, second.generation(), second.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void discardingDoesNotStrandACodeIssuedWhileItRan() {
        // the same shape as the verify race, one level up: a prompt abandoned in one session
        // must not remove the pointer that a code issued meanwhile depends on
        PhoneVerificationManager.IssuedCode first = verifications.issue(USER, NUMBER, config(3));
        AtomicReference<PhoneVerificationManager.IssuedCode> resent = new AtomicReference<>();

        store.onNextRemove(() -> resent.set(verifications.issue(USER, NUMBER, config(3))));
        verifications.discard(USER, first.generation());

        PhoneVerificationManager.IssuedCode current = resent.get();
        assertThat(current, is(notNullValue()));
        assertThat(verifications.verify(USER, NUMBER, current.generation(), current.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void discardingNeverTouchesThePointer() {
        // stronger than watching what discard leaves behind: it must not read or remove the
        // pointer at all, because doing either is what let a stale session strand a newer code
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        store.forgetTouchedKeys();

        verifications.discard(USER, issued.generation());

        for (String key : store.touchedKeys()) {
            assertThat("discard touched the pointer: " + key,
                    key.contains(issued.generation()), is(true));
        }
    }

    @Test
    public void refusesAnAnswerThatCarriesNoGeneration() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));

        assertThat(verifications.verify(USER, NUMBER, null, issued.code()),
                is(PhoneVerificationManager.Result.STALE));
        assertThat(verifications.verify(USER, NUMBER, "nonsense", issued.code()),
                is(PhoneVerificationManager.Result.STALE));

        // and none of that spent an attempt
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), issued.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void doesNotSpendAnAttemptOnAnEmptyAnswer() {
        // an accidental empty submit must not cost one of the few guesses the user has
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(1));
        String code = issued.code();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), ""), is(PhoneVerificationManager.Result.INVALID));
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), null), is(PhoneVerificationManager.Result.INVALID));

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void neverPutsAPhoneNumberInAKey() {
        // keys reach caches, logs and, in a cluster, the wire
        verifications.issue(USER, NUMBER, config(3));

        assertThat(store.keys().stream().anyMatch(key -> key.contains(NUMBER)), is(false));
        assertThat(store.keys().stream().anyMatch(key -> key.contains("5555555")), is(false));
    }

    @Test
    public void keepsNamespacesApart() {
        PhoneVerificationManager login = new PhoneVerificationManager(store, "login");
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));

        assertThat(login.verify(USER, NUMBER, issued.generation(), issued.code()),
                is(PhoneVerificationManager.Result.EXPIRED));
        assertThat(verifications.verify(USER, NUMBER, issued.generation(), issued.code()),
                is(PhoneVerificationManager.Result.VERIFIED));
    }

    @Test
    public void storesNothingThatContainsTheCode() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        assertThat(store.everyStoredValue().stream().anyMatch(value -> value.contains(code)), is(false));
    }

    @Test
    public void forgetsTheCodeWhenAskedTo() {
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();

        verifications.discard(USER, issued.generation());

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.EXPIRED));
    }

    @Test
    public void expiresTheCodeWithTheConfiguredLifespan() {
        verifications.issue(USER, NUMBER, new PhoneVerificationConfig(6, 120, 3, 60));

        assertThat(store.longestLifespan(), is(120L));
    }

    @Test
    public void toleratesAStoreThatLostTheAttemptTokens() {
        // tokens and the code expire together, but nothing guarantees a cache drops them at
        // the same instant, and losing them must fail closed
        PhoneVerificationManager.IssuedCode issued = verifications.issue(USER, NUMBER, config(3));
        String code = issued.code();
        store.removeAttemptTokens();

        assertThat(verifications.verify(USER, NUMBER, issued.generation(), code), is(PhoneVerificationManager.Result.EXHAUSTED));
    }

    private PhoneVerificationConfig config(int attempts) {
        return new PhoneVerificationConfig(6, 300, attempts, 60);
    }

    private String wrong(String code) {
        return (code.startsWith("0") ? "1" : "0") + code.substring(1);
    }
}
