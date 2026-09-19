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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.keycloak.models.SingleUseObjectProvider;

/**
 * An in-memory {@link SingleUseObjectProvider} that keeps the guarantees the real ones make.
 *
 * <p>Only two of them matter here, and both are what the attempt limit is built on: exactly
 * one caller of {@code remove} for a given key can be told it removed something, and exactly
 * one caller of {@code putIfAbsent} can be told it created it. {@link ConcurrentHashMap}
 * provides both, so the concurrency tests exercise the same race the real store would see.
 *
 * <p>Nothing expires here. Lifespans are recorded so tests can assert what was asked for.
 */
class FakeSingleUseObjects implements SingleUseObjectProvider {

    private final Map<String, Map<String, String>> objects = new ConcurrentHashMap<>();
    private final Map<String, Long> lifespans = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> onNextRead = new AtomicReference<>();
    private final AtomicReference<Runnable> afterAttemptClaim = new AtomicReference<>();
    private final AtomicReference<Runnable> onNextRemove = new AtomicReference<>();
    private final List<String> touched = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void put(String key, long lifespanSeconds, Map<String, String> notes) {
        objects.put(key, Map.copyOf(notes));
        lifespans.put(key, lifespanSeconds);
    }

    @Override
    public Map<String, String> get(String key) {
        touched.add(key);
        Map<String, String> value = objects.get(key);
        Runnable interleaved = onNextRead.getAndSet(null);
        if (interleaved != null) {
            // lets a test drop another operation into the exact gap after a read
            interleaved.run();
        }
        return value;
    }

    @Override
    public Map<String, String> remove(String key) {
        touched.add(key);
        Map<String, String> removed = objects.remove(key);
        Runnable interleavedRemoval = onNextRemove.getAndSet(null);
        if (interleavedRemoval != null) {
            interleavedRemoval.run();
        }
        if (key.contains(PhoneVerificationManager.ATTEMPT_MARKER) && removed != null) {
            Runnable interleaved = afterAttemptClaim.getAndSet(null);
            if (interleaved != null) {
                // the gap that matters: an attempt has been spent, the code is about to be
                // compared and removed
                interleaved.run();
            }
        }
        return removed;
    }

    @Override
    public boolean replace(String key, Map<String, String> notes) {
        return objects.replace(key, Map.copyOf(notes)) != null;
    }

    @Override
    public boolean putIfAbsent(String key, long lifespanInSeconds) {
        boolean absent = objects.putIfAbsent(key, Map.of()) == null;
        if (absent) {
            lifespans.put(key, lifespanInSeconds);
        }
        return absent;
    }

    @Override
    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    @Override
    public void close() {
    }

    Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    List<String> everyStoredValue() {
        List<String> values = new ArrayList<>();
        objects.values().forEach(notes -> values.addAll(notes.values()));
        return values;
    }

    long longestLifespan() {
        return lifespans.values().stream().mapToLong(Long::longValue).max().orElse(-1);
    }

    /**
     * Runs {@code action} once, immediately after the next read.
     */
    void onNextRead(Runnable action) {
        onNextRead.set(action);
    }

    /**
     * Runs {@code action} once, immediately after an attempt token is claimed, which is where
     * a resend would have to land to race the removal that follows it.
     */
    void afterAttemptClaim(Runnable action) {
        afterAttemptClaim.set(action);
    }

    /**
     * Runs {@code action} once, immediately after the next removal.
     */
    void onNextRemove(Runnable action) {
        onNextRemove.set(action);
    }

    /**
     * Every key read or removed since {@link #forgetTouchedKeys()}, so a test can assert what
     * an operation is allowed to reach rather than only what it leaves behind.
     */
    List<String> touchedKeys() {
        synchronized (touched) {
            return List.copyOf(touched);
        }
    }

    void forgetTouchedKeys() {
        touched.clear();
    }

    void removeAttemptTokens() {
        objects.keySet().removeIf(key -> key.contains(PhoneVerificationManager.ATTEMPT_MARKER));
    }
}
