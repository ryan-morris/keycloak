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

package org.keycloak.authentication.requiredactions;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.keycloak.Config;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.requiredactions.util.EmailCooldownManager;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredActionConfigModel;
import org.keycloak.models.RequiredActionProviderModel;
import org.keycloak.models.UserModel;
import org.keycloak.phone.PhoneAttributes;
import org.keycloak.phone.PhoneMessage;
import org.keycloak.phone.PhoneMessageException;
import org.keycloak.phone.PhoneMessageSenderProvider;
import org.keycloak.phone.PhoneMessageSenders;
import org.keycloak.phone.PhoneMessageSenders.ConfiguredSender;
import org.keycloak.phone.PhoneVerificationConfig;
import org.keycloak.phone.PhoneVerificationManager;
import org.keycloak.policy.MaxAuthAgePasswordPolicyProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.theme.Theme;

import org.jboss.logging.Logger;

/**
 * Verifies the phone number of a user by sending a code to it, see {@link VerifyEmail}.
 */
public class VerifyPhoneNumber implements RequiredActionProvider, RequiredActionFactory {

    public static final String PROVIDER_ID = UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name();

    static final String FORM = "login-verify-phone-number.ftl";
    static final String FIELD_CODE = "code";
    static final String FIELD_SENDER = "senderId";
    static final String FIELD_GENERATION = "generation";
    static final String ACTION_RESEND = "resend";
    static final String MESSAGE_BODY_KEY = "phoneVerificationMessage";
    static final String RESEND_COOLDOWN_KEY_PREFIX = "verify-phone-number-cooldown-";

    private static final String NOTE_SENT_TO = "VERIFY_PHONE_NUMBER_SENT_TO";
    private static final String NOTE_SENDER = "VERIFY_PHONE_NUMBER_SENDER";
    private static final String NOTE_GENERATION = "VERIFY_PHONE_NUMBER_GENERATION";

    private static final Logger logger = Logger.getLogger(VerifyPhoneNumber.class);

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        UserModel user = context.getUser();

        RequiredActionProviderModel action = context.getRealm().getRequiredActionProviderByAlias(PROVIDER_ID);
        if (action == null || !action.isDefaultAction()) {
            return;
        }
        if (PhoneAttributes.isPhoneNumberVerified(user)) {
            return;
        }
        if (Validation.isBlank(PhoneAttributes.phoneNumber(user))) {
            return;
        }
        user.addRequiredAction(UserModel.RequiredAction.VERIFY_PHONE_NUMBER);
    }

    @Override
    public InitiatedActionSupport initiatedActionSupport() {
        return InitiatedActionSupport.SUPPORTED;
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        UserModel user = context.getUser();

        if (PhoneAttributes.isPhoneNumberVerified(user)) {
            context.success();
            return;
        }

        String number = PhoneAttributes.phoneNumber(user);
        if (Validation.isBlank(number)) {
            context.ignore();
            return;
        }

        List<ConfiguredSender> senders = PhoneMessageSenders.configured(context.getSession(), context.getRealm());
        if (senders.isEmpty()) {
            context.challenge(form(context, senders)
                    .setError(Messages.PHONE_VERIFICATION_UNAVAILABLE)
                    .createForm(FORM));
            return;
        }

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        // Do not send again on a page refresh
        if (number.equals(authSession.getAuthNote(NOTE_SENT_TO))) {
            context.challenge(form(context, senders).createForm(FORM));
            return;
        }

        if (authSession.getAuthNote(NOTE_SENT_TO) != null) {
            // the number changed since the code was sent
            verifications(context.getSession()).discard(user.getId(), authSession.getAuthNote(NOTE_GENERATION));
            forget(authSession);
        }

        // When triggered from AIA, or when there is a sender to choose, wait for the user to ask for the code
        if (isCurrentActionTriggeredFromAIA(context) || senders.size() > 1) {
            context.challenge(form(context, senders).createForm(FORM));
            return;
        }

        Long remaining = EmailCooldownManager.retrieveCooldownEntry(context.getSession(),
                RESEND_COOLDOWN_KEY_PREFIX, user.getId());
        if (remaining != null) {
            context.challenge(form(context, senders)
                    .setError(Messages.COOLDOWN_VERIFICATION_PHONE, remaining)
                    .createForm(FORM));
            return;
        }

        send(context, senders, chooseSender(senders, null), number);
    }

    private boolean isCurrentActionTriggeredFromAIA(RequiredActionContext context) {
        return Objects.equals(context.getAuthenticationSession().getClientNote(Constants.KC_ACTION), getId());
    }

    @Override
    public void processAction(RequiredActionContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        UserModel user = context.getUser();
        String number = PhoneAttributes.phoneNumber(user);

        if (Validation.isBlank(number)) {
            // ignore() is not allowed in processAction
            context.success();
            return;
        }

        List<ConfiguredSender> senders = PhoneMessageSenders.configured(context.getSession(), context.getRealm());
        if (senders.isEmpty()) {
            context.challenge(form(context, senders)
                    .setError(Messages.PHONE_VERIFICATION_UNAVAILABLE)
                    .createForm(FORM));
            return;
        }

        if (formData.containsKey(ACTION_RESEND)) {
            Long remaining = EmailCooldownManager.retrieveCooldownEntry(context.getSession(),
                    RESEND_COOLDOWN_KEY_PREFIX, user.getId());
            if (remaining != null) {
                context.challenge(form(context, senders)
                        .setError(Messages.COOLDOWN_VERIFICATION_PHONE, remaining)
                        .createForm(FORM));
                return;
            }
            send(context, senders, chooseSender(senders, formData.getFirst(FIELD_SENDER)), number);
            return;
        }

        EventBuilder event = context.getEvent().clone()
                .event(EventType.VERIFY_PHONE_NUMBER)
                .detail(Details.USERNAME, user.getUsername());

        PhoneVerificationManager.Result result = verifications(context.getSession()).verify(user.getId(), number,
                formData.getFirst(FIELD_GENERATION), formData.getFirst(FIELD_CODE));

        if (result == PhoneVerificationManager.Result.VERIFIED) {
            PhoneAttributes.setPhoneNumberVerified(user, true);
            forget(context.getAuthenticationSession());
            event.success();
            context.success();
            return;
        }

        if (result == PhoneVerificationManager.Result.NUMBER_CHANGED) {
            forget(context.getAuthenticationSession());
        }
        // the same error whether the code is wrong, expired or used up
        event.error(Errors.INVALID_CODE);
        context.challenge(form(context, senders)
                .setError(Messages.INVALID_PHONE_CODE)
                .createForm(FORM));
    }

    private void send(RequiredActionContext context, List<ConfiguredSender> senders, ConfiguredSender sender, String number) {
        KeycloakSession session = context.getSession();
        UserModel user = context.getUser();
        PhoneVerificationConfig config = config(context);
        PhoneVerificationManager verifications = verifications(session);

        // Adding the cooldown entry first to prevent concurrent operations
        if (config.resendCooldownSeconds() > 0) {
            EmailCooldownManager.addCooldownEntry(session, RESEND_COOLDOWN_KEY_PREFIX, user.getId(),
                    config.resendCooldownSeconds());
        }

        PhoneVerificationManager.IssuedCode issued = verifications.issue(user.getId(), number, config);
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(NOTE_GENERATION, issued.generation());
        authSession.setAuthNote(NOTE_SENT_TO, number);
        authSession.setAuthNote(NOTE_SENDER, sender.id());

        EventBuilder event = context.getEvent().clone()
                .event(EventType.SEND_VERIFY_PHONE_NUMBER)
                .detail(Details.USERNAME, user.getUsername());

        PhoneMessageSenderProvider provider = null;
        try {
            provider = sender.create(session);
            if (provider == null) {
                throw new PhoneMessageException("Sender " + sender.id() + " could not be created");
            }
            Locale locale = session.getContext().resolveLocale(user);
            provider.send(number, new PhoneMessage(body(context, issued.code(), config, locale),
                    PhoneMessage.PURPOSE_VERIFY_PHONE_NUMBER, locale));
        } catch (PhoneMessageException e) {
            verifications.discard(user.getId(), issued.generation());
            authSession.removeAuthNote(NOTE_GENERATION);
            event.clone().event(EventType.SEND_VERIFY_PHONE_NUMBER)
                    .detail(Details.REASON, e.getMessage())
                    .user(user)
                    .error(Errors.PHONE_MESSAGE_SEND_FAILED);
            logger.error("Failed to send phone number verification message", e);
            context.failure(Messages.PHONE_SENT_ERROR);
            context.challenge(context.form()
                    .setError(Messages.PHONE_SENT_ERROR)
                    .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        } finally {
            if (provider != null) {
                provider.close();
            }
        }

        event.success();

        context.challenge(form(context, senders).createForm(FORM));
    }

    private ConfiguredSender chooseSender(List<ConfiguredSender> senders, String requestedId) {
        return senders.stream()
                .filter(sender -> sender.id().equals(requestedId))
                .findFirst()
                .orElse(senders.get(0));
    }

    private LoginFormsProvider form(RequiredActionContext context, List<ConfiguredSender> senders) {
        UserModel user = context.getUser();
        String number = PhoneAttributes.phoneNumber(user);

        String generation = context.getAuthenticationSession().getAuthNote(NOTE_GENERATION);

        return context.form()
                .setAuthenticationSession(context.getAuthenticationSession())
                .setAttribute("phoneNumber", number == null ? "" : masked(number))
                .setAttribute("senders", senders.stream()
                        .map(sender -> new SenderBean(sender.id(), sender.displayName(), sender.channel()))
                        .toList())
                .setAttribute("selectedSender",
                        Objects.requireNonNullElse(context.getAuthenticationSession().getAuthNote(NOTE_SENDER), ""))
                .setAttribute("codeSent", generation != null)
                .setAttribute("generation", Objects.requireNonNullElse(generation, ""));
    }

    // only the last digits are shown
    static String masked(String number) {
        String trimmed = number.trim();
        int visible = 2;
        if (trimmed.length() <= visible * 2) {
            return "•••";
        }
        return "••• " + trimmed.substring(trimmed.length() - visible);
    }

    private String body(RequiredActionContext context, String code, PhoneVerificationConfig config, Locale locale) {
        RealmModel realm = context.getRealm();
        long minutes = Math.max(1, TimeUnit.SECONDS.toMinutes(config.codeLifespanSeconds()));

        try {
            Theme theme = context.getSession().theme().getTheme(Theme.Type.LOGIN);
            // includes the realm localization overrides
            Properties messages = theme.getEnhancedMessages(realm, locale);
            String pattern = messages.getProperty(MESSAGE_BODY_KEY);
            if (pattern != null) {
                return new MessageFormat(pattern, locale).format(new Object[] { code, minutes });
            }
        } catch (IOException e) {
            logger.warn("Failed to load the login theme messages", e);
        }
        return code;
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

    private void forget(AuthenticationSessionModel authSession) {
        authSession.removeAuthNote(NOTE_SENT_TO);
        authSession.removeAuthNote(NOTE_SENDER);
        authSession.removeAuthNote(NOTE_GENERATION);
    }

    private PhoneVerificationManager verifications(KeycloakSession session) {
        return new PhoneVerificationManager(session.singleUseObjects());
    }

    private PhoneVerificationConfig config(RequiredActionContext context) {
        RequiredActionConfigModel model = context.getConfig();
        return PhoneVerificationConfig.of(model == null ? null : model.getConfig());
    }

    @Override
    public void initiatedActionCanceled(KeycloakSession session, AuthenticationSessionModel authSession) {
        // the code is of no further use
        UserModel user = authSession.getAuthenticatedUser();
        if (user != null) {
            verifications(session).discard(user.getId(), authSession.getAuthNote(NOTE_GENERATION));
        }
        forget(authSession);
    }

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "Verify Phone Number";
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        ProviderConfigProperty codeLength = new ProviderConfigProperty();
        codeLength.setName(PhoneVerificationConfig.CODE_LENGTH);
        codeLength.setLabel("Code length");
        codeLength.setHelpText("How many digits the verification code has. Between 6 and 12.");
        codeLength.setType(ProviderConfigProperty.INTEGER_TYPE);
        codeLength.setDefaultValue(PhoneVerificationConfig.DEFAULT_CODE_LENGTH);

        ProviderConfigProperty lifespan = new ProviderConfigProperty();
        lifespan.setName(PhoneVerificationConfig.CODE_LIFESPAN);
        lifespan.setLabel("Code lifespan");
        lifespan.setHelpText("How long, in seconds, a verification code can be used for.");
        lifespan.setType(ProviderConfigProperty.INTEGER_TYPE);
        lifespan.setDefaultValue(PhoneVerificationConfig.DEFAULT_CODE_LIFESPAN_SECONDS);

        ProviderConfigProperty attempts = new ProviderConfigProperty();
        attempts.setName(PhoneVerificationConfig.MAX_ATTEMPTS);
        attempts.setLabel("Maximum attempts");
        attempts.setHelpText("How many times a code can be entered incorrectly before it stops working "
                + "and a new one has to be requested. Between 1 and 10.");
        attempts.setType(ProviderConfigProperty.INTEGER_TYPE);
        attempts.setDefaultValue(PhoneVerificationConfig.DEFAULT_MAX_ATTEMPTS);

        ProviderConfigProperty cooldown = new ProviderConfigProperty();
        cooldown.setName(PhoneVerificationConfig.RESEND_COOLDOWN);
        cooldown.setLabel("Resend cooldown");
        cooldown.setHelpText("How long, in seconds, before another message can be sent to the same user. "
                + "Messages usually cost money to send.");
        cooldown.setType(ProviderConfigProperty.INTEGER_TYPE);
        cooldown.setDefaultValue(PhoneVerificationConfig.DEFAULT_RESEND_COOLDOWN_SECONDS);

        return List.of(codeLength, lifespan, attempts, cooldown, maxAuthAge());
    }

    private ProviderConfigProperty maxAuthAge() {
        ProviderConfigProperty maxAge = new ProviderConfigProperty();
        maxAge.setName(Constants.MAX_AUTH_AGE_KEY);
        maxAge.setLabel("Maximum Age of Authentication");
        maxAge.setHelpText("Configures the duration in seconds this action can be used after the last authentication "
                + "before the user is required to re-authenticate. This parameter is used just in the context of AIA "
                + "when the kc_action parameter is available in the request.");
        maxAge.setType(ProviderConfigProperty.STRING_TYPE);
        maxAge.setDefaultValue(MaxAuthAgePasswordPolicyProviderFactory.DEFAULT_MAX_AUTH_AGE);
        return maxAge;
    }
}
