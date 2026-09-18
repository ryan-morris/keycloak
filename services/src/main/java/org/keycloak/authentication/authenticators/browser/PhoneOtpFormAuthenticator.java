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

package org.keycloak.authentication.authenticators.browser;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.requiredactions.util.EmailCooldownManager;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.phone.PhoneAttributes;
import org.keycloak.phone.PhoneCodePrompt;
import org.keycloak.phone.PhoneMessage;
import org.keycloak.phone.PhoneMessageException;
import org.keycloak.phone.PhoneMessageSenders;
import org.keycloak.phone.PhoneMessageSenders.ConfiguredSender;
import org.keycloak.phone.PhoneVerificationConfig;
import org.keycloak.phone.PhoneVerificationManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.sessions.AuthenticationSessionModel;

import org.jboss.logging.Logger;

/**
 * Asks for a code sent to the verified phone number of the user, see {@link OTPFormAuthenticator}.
 */
public class PhoneOtpFormAuthenticator extends AbstractUsernameFormAuthenticator implements Authenticator {

    public static final String PURPOSE = "login";

    static final String FORM = "login-phone-otp.ftl";
    static final String FIELD_CODE = "code";
    static final String FIELD_SENDER = "senderId";
    static final String FIELD_GENERATION = "generation";
    static final String ACTION_RESEND = "resend";
    static final String MESSAGE_BODY_KEY = "phoneOtpMessage";
    static final String RESEND_COOLDOWN_KEY_PREFIX = "phone-otp-cooldown-";

    private static final String NOTE_SENT_TO = "PHONE_OTP_SENT_TO";
    private static final String NOTE_SENDER = "PHONE_OTP_SENDER";
    private static final String NOTE_GENERATION = "PHONE_OTP_GENERATION";

    private static final Logger logger = Logger.getLogger(PhoneOtpFormAuthenticator.class);

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        String number = PhoneAttributes.phoneNumber(user);

        List<ConfiguredSender> senders = PhoneMessageSenders.configured(context.getSession(), context.getRealm());
        if (senders.isEmpty()) {
            context.challenge(form(context, senders)
                    .setError(Messages.PHONE_VERIFICATION_UNAVAILABLE)
                    .createForm(FORM));
            return;
        }

        // Do not send again on a page refresh
        if (number.equals(context.getAuthenticationSession().getAuthNote(NOTE_SENT_TO))) {
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

        send(context, senders, PhoneCodePrompt.chooseSender(senders, null), number);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        UserModel user = context.getUser();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        List<ConfiguredSender> senders = PhoneMessageSenders.configured(context.getSession(), context.getRealm());
        form(context, senders);

        boolean userEnabled = enabledUser(context, user);
        // the brute force lock might be lifted/user enabled in the meantime -> we need to clear the auth session note
        if (userEnabled) {
            authSession.removeAuthNote(AbstractUsernameFormAuthenticator.SESSION_INVALID);
        }
        if ("true".equals(authSession.getAuthNote(AbstractUsernameFormAuthenticator.SESSION_INVALID))) {
            context.getEvent().user(user).error(Errors.INVALID_AUTHENTICATION_SESSION);
            // challenge already set by calling enabledUser() above
            return;
        }
        if (!userEnabled) {
            // error in context is set in enabledUser/isDisabledByBruteForce
            authSession.setAuthNote(AbstractUsernameFormAuthenticator.SESSION_INVALID, "true");
            return;
        }

        PhoneVerificationManager verifications = verifications(context.getSession());
        String number = PhoneAttributes.phoneNumber(user);
        String sentTo = authSession.getAuthNote(NOTE_SENT_TO);
        if (!configuredFor(context.getSession(), context.getRealm(), user) || (sentTo != null && !sentTo.equals(number))) {
            verifications.discard(user.getId(), authSession.getAuthNote(NOTE_GENERATION));
            forget(authSession);
            context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            return;
        }

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
            send(context, senders, PhoneCodePrompt.chooseSender(senders, formData.getFirst(FIELD_SENDER)), number);
            return;
        }

        PhoneVerificationManager.Result result = verifications.verify(user.getId(), number,
                formData.getFirst(FIELD_GENERATION), formData.getFirst(FIELD_CODE));

        if (result != PhoneVerificationManager.Result.VERIFIED) {
            // the same error whether the code is wrong, expired or used up
            context.getEvent().user(user).error(Errors.INVALID_USER_CREDENTIALS);
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    challenge(context, Messages.INVALID_PHONE_CODE));
            return;
        }

        forget(authSession);
        context.success(PhoneOtpFormAuthenticatorFactory.REFERENCE_CATEGORY);
    }

    private void send(AuthenticationFlowContext context, List<ConfiguredSender> senders, ConfiguredSender sender, String number) {
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
                .user(user)
                .detail(Details.USERNAME, user.getUsername());

        try {
            Locale locale = session.getContext().resolveLocale(user);
            String body = PhoneCodePrompt.body(session, context.getRealm(), MESSAGE_BODY_KEY, issued.code(), config, locale);
            PhoneCodePrompt.send(session, sender, number, new PhoneMessage(body, PURPOSE, locale));
        } catch (PhoneMessageException e) {
            verifications.discard(user.getId(), issued.generation());
            authSession.removeAuthNote(NOTE_GENERATION);
            event.detail(Details.REASON, e.getMessage())
                    .error(Errors.PHONE_MESSAGE_SEND_FAILED);
            logger.error("Failed to send phone login code", e);
            // not failure(), which counts as a failed login of the user
            context.challenge(context.form()
                    .setError(Messages.PHONE_SENT_ERROR)
                    .createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
            return;
        }

        event.success();

        context.challenge(form(context, senders).createForm(FORM));
    }

    private LoginFormsProvider form(AuthenticationFlowContext context, List<ConfiguredSender> senders) {
        String number = PhoneAttributes.phoneNumber(context.getUser());
        AuthenticationSessionModel authSession = context.getAuthenticationSession();

        String generation = authSession.getAuthNote(NOTE_GENERATION);

        return context.form()
                .setExecution(context.getExecution().getId())
                .setAttribute("phoneNumber", number == null ? "" : PhoneCodePrompt.masked(number))
                .setAttribute("senders", PhoneCodePrompt.senderBeans(senders))
                .setAttribute("selectedSender", Objects.requireNonNullElse(authSession.getAuthNote(NOTE_SENDER), ""))
                .setAttribute("codeSent", generation != null)
                .setAttribute("generation", Objects.requireNonNullElse(generation, ""));
    }

    private void forget(AuthenticationSessionModel authSession) {
        authSession.removeAuthNote(NOTE_SENT_TO);
        authSession.removeAuthNote(NOTE_SENDER);
        authSession.removeAuthNote(NOTE_GENERATION);
    }

    private PhoneVerificationManager verifications(KeycloakSession session) {
        // a code sent to verify a number must not log anybody in
        return new PhoneVerificationManager(session.singleUseObjects(), PURPOSE);
    }

    private PhoneVerificationConfig config(AuthenticationFlowContext context) {
        AuthenticatorConfigModel model = context.getAuthenticatorConfig();
        return PhoneVerificationConfig.of(model == null ? null : model.getConfig());
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    protected String disabledByBruteForceError(String error) {
        return Messages.INVALID_PHONE_CODE;
    }

    @Override
    protected String disabledByBruteForceFieldError() {
        return null;
    }

    @Override
    protected Response createLoginForm(LoginFormsProvider form) {
        return form.createForm(FORM);
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // not whether a sender is configured, a conditional flow would skip the step when there is none
        return PhoneAttributes.isPhoneNumberVerified(user)
                && !Validation.isBlank(PhoneAttributes.phoneNumber(user));
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {

    }

    @Override
    public void close() {

    }
}
