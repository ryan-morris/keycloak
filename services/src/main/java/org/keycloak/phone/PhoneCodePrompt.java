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

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.phone.PhoneMessageSenders.ConfiguredSender;
import org.keycloak.theme.Theme;

import org.jboss.logging.Logger;

/**
 * What the forms asking for a code sent to a phone number have in common.
 */
public final class PhoneCodePrompt {

    private static final Logger logger = Logger.getLogger(PhoneCodePrompt.class);

    private PhoneCodePrompt() {
    }

    public static List<SenderBean> senderBeans(List<ConfiguredSender> senders) {
        return senders.stream()
                .map(sender -> new SenderBean(sender.id(), sender.displayName(), sender.channel()))
                .toList();
    }

    /**
     * The requested sender, or the first one configured.
     */
    public static ConfiguredSender chooseSender(List<ConfiguredSender> senders, String requestedId) {
        return senders.stream()
                .filter(sender -> sender.id().equals(requestedId))
                .findFirst()
                .orElse(senders.get(0));
    }

    // only the last digits are shown
    public static String masked(String number) {
        String trimmed = number.trim();
        int visible = 2;
        if (trimmed.length() <= visible * 2) {
            return "•••";
        }
        return "••• " + trimmed.substring(trimmed.length() - visible);
    }

    public static String body(KeycloakSession session, RealmModel realm, String messageKey, String code,
            PhoneVerificationConfig config, Locale locale) {
        long minutes = Math.max(1, TimeUnit.SECONDS.toMinutes(config.codeLifespanSeconds()));

        try {
            Theme theme = session.theme().getTheme(Theme.Type.LOGIN);
            // includes the realm localization overrides
            Properties messages = theme.getEnhancedMessages(realm, locale);
            String pattern = messages.getProperty(messageKey);
            if (pattern != null) {
                return new MessageFormat(pattern, locale).format(new Object[] { code, minutes });
            }
        } catch (IOException e) {
            logger.warn("Failed to load the login theme messages", e);
        }
        return code;
    }

    public static void send(KeycloakSession session, ConfiguredSender sender, String number, PhoneMessage message)
            throws PhoneMessageException {
        PhoneMessageSenderProvider provider = null;
        try {
            provider = sender.create(session);
            if (provider == null) {
                throw new PhoneMessageException("Sender " + sender.id() + " could not be created");
            }
            provider.send(number, message);
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    public static class SenderBean {

        private final String id;
        private final String displayName;
        private final String channel;

        SenderBean(String id, String displayName, String channel) {
            this.id = id;
            this.displayName = displayName;
            this.channel = channel;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getChannel() {
            return channel;
        }
    }
}
