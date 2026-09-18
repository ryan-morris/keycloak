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

package org.keycloak.tests.authentication;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.UserModel;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderSimpleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.testframework.annotations.InjectEvents;
import org.keycloak.testframework.annotations.InjectHttpClient;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.events.EventAssertion;
import org.keycloak.testframework.events.Events;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.page.ErrorPage;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.page.LoginPhoneOtpPage;
import org.keycloak.testframework.ui.page.LoginVerifyPhoneNumberPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.keycloak.testframework.util.ApiUtil;
import org.keycloak.tests.common.CustomProvidersServerConfig;
import org.keycloak.tests.utils.PasswordGenerateUtil;
import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * A code sent to a verified phone number, used as a step in the browser flow.
 *
 * <p>The realm gets a copy of the browser flow with this step added after the password, which
 * is how an administrator would set it up.
 */
@KeycloakIntegrationTest(config = CustomProvidersServerConfig.class)
public class PhoneOtpFormAuthenticatorTest {

    private static final String PASSWORD = PasswordGenerateUtil.generatePassword();
    private static final String NUMBER = "+15555550123";
    private static final String SENDER_TYPE = "org.keycloak.phone.PhoneMessageSenderProvider";
    private static final String FLOW = "browser with phone otp";
    private static final String SUBFLOW = "phone otp conditional";

    @InjectRealm(lifecycle = LifeCycle.METHOD)
    ManagedRealm realm;

    @InjectWebDriver
    ManagedWebDriver driver;

    @InjectOAuthClient
    OAuthClient oauth;

    @InjectPage
    LoginPage loginPage;

    @InjectPage
    LoginPhoneOtpPage phoneOtpPage;

    @InjectPage
    LoginVerifyPhoneNumberPage verifyPhoneNumberPage;

    @InjectPage
    ErrorPage errorPage;

    @InjectEvents
    Events events;

    @InjectHttpClient
    HttpClient httpClient;

    @BeforeEach
    public void setUp() {
        declareAttributes();
        forgetMessages();
        configureSender();
        useFlowWithPhoneOtp();
    }

    @Test
    public void asksForACodeAndLetsTheUserIn() {
        createUser("verified", NUMBER, true);

        login("verified");

        phoneOtpPage.assertCurrent();
        phoneOtpPage.submit(codeFromLastMessage());

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
    }

    @Test
    public void sendsASignInCode() {
        createUser("wording", NUMBER, true);

        login("wording");

        assertThat(lastMessage().get("text"), containsString("is your sign-in code"));
        Assertions.assertEquals("login", lastMessage().get("purpose"));
    }

    @Test
    public void refusesAWrongCode() {
        String userId = createUser("wrong", NUMBER, true);
        protectAgainstBruteForce();

        login("wrong");
        phoneOtpPage.submit(wrong(codeFromLastMessage()));

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("Invalid verification code"));
        awaitNumFailures(userId, 1);
    }

    @Test
    public void stopsAcceptingGuesses() {
        createUser("exhausted", NUMBER, true);
        configureStep(Map.of("maxAttempts", "2"));

        login("exhausted");
        String code = codeFromLastMessage();

        phoneOtpPage.submit(wrong(code));
        phoneOtpPage.submit(wrong(code));
        // the real code, after the guesses are used up
        phoneOtpPage.submit(code);

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("Invalid verification code"));
    }

    @Test
    public void showsItsOwnFormToALockedOutUser() {
        String userId = createUser("locked-out", NUMBER, true);
        protectAgainstBruteForce();

        login("locked-out");
        String code = codeFromLastMessage();
        phoneOtpPage.submit(wrong(code));
        awaitNumFailures(userId, 1);

        phoneOtpPage.submit(code);

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("Invalid verification code"));
    }

    @Test
    public void refusesAResendAfterTheNumberIsTakenAway() {
        String userId = createUser("removed-number", NUMBER, true);
        configureStep(Map.of("resendCooldownSeconds", "0"));

        login("removed-number");
        updateAttributes(userId, Map.of());

        phoneOtpPage.resend();

        errorPage.assertCurrent();
        Assertions.assertEquals(1, messageCount(), "no further message should have been sent");
    }

    @Test
    public void refusesTheCodeAfterTheNumberIsNoLongerVerified() {
        String userId = createUser("unverified-meanwhile", NUMBER, true);

        login("unverified-meanwhile");
        String code = codeFromLastMessage();
        updateAttributes(userId, Map.of("phoneNumber", List.of(NUMBER), "phoneNumberVerified", List.of("false")));

        phoneOtpPage.submit(code);

        errorPage.assertCurrent();
    }

    @Test
    public void refusesTheCodeAfterTheNumberChanged() {
        String userId = createUser("changed-number", NUMBER, true);

        login("changed-number");
        String code = codeFromLastMessage();
        updateAttributes(userId, Map.of("phoneNumber", List.of("+15555550199"), "phoneNumberVerified", List.of("true")));

        phoneOtpPage.submit(code);

        errorPage.assertCurrent();
    }

    @Test
    public void holdsOffAResend() {
        createUser("cooldown", NUMBER, true);
        configureStep(Map.of("resendCooldownSeconds", "600"));

        login("cooldown");
        phoneOtpPage.resend();

        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertEquals(1, messageCount(), "no second message should have been sent");
    }

    @Test
    public void holdsOffASecondLogin() {
        createUser("second-login", NUMBER, true);
        configureStep(Map.of("resendCooldownSeconds", "600"));

        login("second-login");
        phoneOtpPage.assertCurrent();
        Assertions.assertEquals(1, messageCount());

        login("second-login");

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertFalse(phoneOtpPage.isCodeInputDisplayed(), "no code was sent in this login");
        Assertions.assertEquals(1, messageCount(), "a new login must not send within the cooldown");
    }

    @Test
    public void doesNotSendAgainWhenThePromptIsReloaded() {
        createUser("refresh", NUMBER, true);

        login("refresh");
        String code = codeFromLastMessage();

        driver.driver().navigate().refresh();

        Assertions.assertEquals(1, messageCount(), "reloading should not send another message");
        phoneOtpPage.submit(code);
        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
    }

    @Test
    public void skipsAUserWithAnUnverifiedNumber() {
        createUser("unverified", NUMBER, false);

        login("unverified");

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
        Assertions.assertNull(lastMessage(), "nothing should have been sent");
    }

    @Test
    public void skipsAUserWithoutANumber() {
        createUser("no-number", null, false);

        login("no-number");

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
        Assertions.assertNull(lastMessage());
    }

    @Test
    public void refusesAUserWithAnUnverifiedNumberWhenRequired() {
        requireTheStepForEveryone();
        createUser("required-unverified", NUMBER, false);

        login("required-unverified");

        errorPage.assertCurrent();
        Assertions.assertEquals(0, messageCount());
    }

    @Test
    public void refusesAUserWithoutANumberWhenRequired() {
        requireTheStepForEveryone();
        enableVerifyPhoneNumber();
        createUser("required-no-number", null, false);

        login("required-no-number");

        errorPage.assertCurrent();
    }

    @Test
    public void saysSoWhenNothingCanSend() {
        removeSenders();
        createUser("no-sender", NUMBER, true);

        login("no-sender");

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("not available"));
    }

    @Test
    public void reportsAFailureToSend() {
        removeSenders();
        configureSender(Map.of("apiKey", "k", "fail", "true"));
        String userId = createUser("failing", NUMBER, true);
        protectAgainstBruteForce();

        login("failing");

        EventAssertion.assertError(events.poll())
                .type(EventType.SEND_VERIFY_PHONE_NUMBER_ERROR)
                .error(Errors.PHONE_MESSAGE_SEND_FAILED)
                .details(Details.USERNAME, "failing")
                .userId(userId);

        errorPage.assertCurrent();
        assertThat(errorPage.getError(), is("Failed to send the code, please try again later."));

        // the user is not locked out by it
        login("failing");

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertEquals(0, realm.admin().attackDetection().bruteForceUserStatus(userId).get("numFailures"));
    }

    @Test
    public void keepsTheCooldownWhenSendingFailed() {
        removeSenders();
        configureSender(Map.of("apiKey", "k", "failAfterSending", "true"));
        createUser("failed-cooldown", NUMBER, true);
        configureStep(Map.of("resendCooldownSeconds", "600"));

        login("failed-cooldown");
        errorPage.assertCurrent();
        Assertions.assertEquals(1, messageCount());

        login("failed-cooldown");

        phoneOtpPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertEquals(1, messageCount(), "a failed send must not lift the cooldown");
    }

    /**
     * Configures the phone step in the copied flow.
     */
    private void configureStep(Map<String, String> config) {
        AuthenticationExecutionInfoRepresentation step = realm.admin().flows()
                .getExecutions(FLOW).stream()
                .filter(execution -> "auth-phone-otp-form".equals(execution.getProviderId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the phone step is not in the flow"));

        AuthenticatorConfigRepresentation representation = new AuthenticatorConfigRepresentation();
        representation.setAlias("phone-otp-config");
        representation.setConfig(config);
        realm.admin().flows().newExecutionConfig(step.getId(), representation).close();
    }

    private String wrong(String code) {
        return (code.startsWith("0") ? "1" : "0") + code.substring(1);
    }

    private void updateAttributes(String userId, Map<String, List<String>> attributes) {
        UserRepresentation user = realm.admin().users().get(userId).toRepresentation();
        user.setAttributes(attributes);
        realm.admin().users().get(userId).update(user);
    }

    private void protectAgainstBruteForce() {
        RealmRepresentation representation = realm.admin().toRepresentation();
        representation.setBruteForceProtected(true);
        representation.setFailureFactor(1);
        realm.admin().update(representation);
    }

    private void awaitNumFailures(String userId, int expected) {
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> Assertions.assertEquals(expected,
                        realm.admin().attackDetection().bruteForceUserStatus(userId).get("numFailures")));
    }

    private void requireTheStepForEveryone() {
        require("conditional-user-configured", "DISABLED");
        require(SUBFLOW, "REQUIRED");
    }

    private void enableVerifyPhoneNumber() {
        String alias = UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name();

        if (realm.admin().flows().getRequiredActions().stream().noneMatch(a -> alias.equals(a.getAlias()))) {
            RequiredActionProviderSimpleRepresentation unregistered = realm.admin().flows()
                    .getUnregisteredRequiredActions().stream()
                    .filter(a -> alias.equals(a.getProviderId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("VERIFY_PHONE_NUMBER is not available at all"));
            realm.admin().flows().registerRequiredAction(unregistered);
        }

        RequiredActionProviderRepresentation action = realm.admin().flows().getRequiredAction(alias);
        action.setEnabled(true);
        action.setDefaultAction(false);
        realm.admin().flows().updateRequiredAction(alias, action);
    }

    private int messageCount() {
        return messages().size();
    }

    private void login(String username) {
        oauth.openLoginForm();
        loginPage.fillLogin(username, PASSWORD);
        loginPage.submit();
    }

    private String codeFromLastMessage() {
        Map<String, String> sent = lastMessage();
        Assertions.assertNotNull(sent, "a message should have been sent");
        return sent.get("text").replaceAll("\\D+", "").substring(0, 6);
    }

    private Map<String, String> lastMessage() {
        List<Map<String, String>> sent = messages();
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    private List<Map<String, String>> messages() {
        try {
            HttpResponse response = httpClient.execute(new HttpGet(realm.getBaseUrl() + "/test-phone-messages"));
            return JsonSerialization.readValue(response.getEntity().getContent(), new TypeReference<>() { });
        } catch (IOException unreadable) {
            throw new RuntimeException(unreadable);
        }
    }

    private void forgetMessages() {
        try {
            httpClient.execute(new HttpDelete(realm.getBaseUrl() + "/test-phone-messages"));
        } catch (IOException unreadable) {
            throw new RuntimeException(unreadable);
        }
    }

    private void declareAttributes() {
        UPConfig profile = realm.admin().users().userProfile().getConfiguration();
        for (String name : List.of("phoneNumber", "phoneNumberVerified")) {
            if (profile.getAttribute(name) == null) {
                UPAttribute attribute = new UPAttribute();
                attribute.setName(name);
                UPAttributePermissions permissions = new UPAttributePermissions();
                permissions.setView(Set.of("admin", "user"));
                permissions.setEdit(Set.of("admin"));
                attribute.setPermissions(permissions);
                attribute.setMultivalued(false);
                profile.addOrReplaceAttribute(attribute);
            }
        }
        realm.admin().users().userProfile().update(profile);
    }

    private void configureSender() {
        configureSender(Map.of("apiKey", "secret-key"));
    }

    private void configureSender(Map<String, String> config) {
        ComponentRepresentation component = new ComponentRepresentation();
        component.setName("Text message");
        component.setProviderId("recording-sms");
        component.setProviderType(SENDER_TYPE);
        component.setParentId(realm.getId());
        component.setConfig(new MultivaluedHashMap<>());
        config.forEach((key, value) -> component.getConfig().putSingle(key, value));
        ApiUtil.getCreatedId(realm.admin().components().add(component));
    }

    private void removeSenders() {
        realm.admin().components().query(realm.getId(), SENDER_TYPE)
                .forEach(component -> realm.admin().components().component(component.getId()).remove());
    }

    /**
     * Copies the browser flow, adds the phone step after the password, and binds it.
     */
    private void useFlowWithPhoneOtp() {
        realm.admin().flows().copy("browser", Map.of("newName", FLOW)).close();

        AuthenticationExecutionInfoRepresentation forms = realm.admin().flows()
                .getExecutions(FLOW).stream()
                .filter(execution -> execution.getDisplayName().endsWith("forms"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the copied flow has no forms subflow"));

        // the shape an administrator would build, and the same one the built-in OTP step uses:
        // a conditional subflow that runs only for users the step applies to
        realm.admin().flows().addExecutionFlow(forms.getDisplayName(), Map.of(
                "alias", SUBFLOW,
                "type", "basic-flow",
                "description", "phone otp",
                "provider", "registration-page-form"));

        realm.admin().flows().addExecution(SUBFLOW, Map.of("provider", "conditional-user-configured"));
        realm.admin().flows().addExecution(SUBFLOW, Map.of("provider", "auth-phone-otp-form"));

        require(SUBFLOW, "CONDITIONAL");
        require("conditional-user-configured", "REQUIRED");
        require("auth-phone-otp-form", "REQUIRED");

        RealmRepresentation representation = realm.admin().toRepresentation();
        representation.setBrowserFlow(FLOW);
        realm.admin().update(representation);
    }

    /**
     * Sets the requirement of the execution with this provider id or subflow name.
     *
     * <p>The last match, not the first: the copied browser flow already contains a
     * "Condition - user configured" of its own, and the ones added here come after it.
     */
    private void require(String providerIdOrFlow, String requirement) {
        List<AuthenticationExecutionInfoRepresentation> matches = realm.admin().flows()
                .getExecutions(FLOW).stream()
                .filter(e -> providerIdOrFlow.equals(e.getProviderId())
                        || providerIdOrFlow.equals(e.getDisplayName()))
                .toList();
        if (matches.isEmpty()) {
            throw new AssertionError("no execution for " + providerIdOrFlow);
        }
        AuthenticationExecutionInfoRepresentation execution = matches.get(matches.size() - 1);
        execution.setRequirement(requirement);
        realm.admin().flows().updateExecutions(FLOW, execution);
    }

    private String createUser(String username, String number, boolean verified) {
        UserBuilder user = UserBuilder.create(username).enabled(true).password(PASSWORD)
                .email(username + "@localhost").emailVerified(true).name("Test", "User");
        if (number != null) {
            user.attribute("phoneNumber", number);
            user.attribute("phoneNumberVerified", Boolean.toString(verified));
        }
        return ApiUtil.getCreatedId(realm.admin().users().create(user.build()));
    }
}
