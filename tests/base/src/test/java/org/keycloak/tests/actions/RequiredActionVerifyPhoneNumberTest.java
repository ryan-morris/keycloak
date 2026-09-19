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

package org.keycloak.tests.actions;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.UserModel;
import org.keycloak.phone.PhoneMessage;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RequiredActionConfigRepresentation;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@KeycloakIntegrationTest(config = CustomProvidersServerConfig.class)
public class RequiredActionVerifyPhoneNumberTest {

    private static final String PASSWORD = PasswordGenerateUtil.generatePassword();
    private static final String NUMBER = "+15555550123";
    private static final String SMS_PROVIDER = "recording-sms";
    private static final String VOICE_PROVIDER = "recording-voice";
    private static final String SENDER_TYPE = "org.keycloak.phone.PhoneMessageSenderProvider";

    @InjectRealm(lifecycle = LifeCycle.METHOD)
    ManagedRealm realm;

    @InjectWebDriver
    ManagedWebDriver driver;

    @InjectOAuthClient
    OAuthClient oauth;

    @InjectPage
    LoginPage loginPage;

    @InjectPage
    LoginVerifyPhoneNumberPage verifyPhoneNumberPage;

    @InjectPage
    ErrorPage errorPage;

    @InjectEvents
    Events events;

    @InjectHttpClient
    HttpClient httpClient;

    @BeforeEach
    public void declareAttributesAndEnableTheAction() {
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

        setAction(true, true);
        forgetMessages();
    }

    @Test
    public void verifiesTheNumber() {
        configureSender(SMS_PROVIDER, "Text message");
        String userId = createUser("verifies", NUMBER);

        login("verifies");

        verifyPhoneNumberPage.assertCurrent();
        String code = codeFromLastMessage();
        verifyPhoneNumberPage.submit(code);

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(), "the login should complete");
        Assertions.assertEquals("true", attribute(userId, "phoneNumberVerified"));
        Assertions.assertEquals(NUMBER, attribute(userId, "phoneNumber"),
                "the number itself should be left exactly as it was stored");
    }

    @Test
    public void sendsTheConfiguredWording() {
        configureSender(SMS_PROVIDER, "Text message");
        createUser("wording", NUMBER);

        login("wording");

        Map<String, String> sent = lastMessage();
        Assertions.assertEquals(NUMBER, sent.get("number"));
        Assertions.assertEquals(PhoneMessage.PURPOSE_VERIFY_PHONE_NUMBER, sent.get("purpose"));
        assertThat(sent.get("text"), containsString("is your verification code"));
        assertThat(sent.get("text"), containsString(codeFromLastMessage()));
    }

    @Test
    public void doesNotShowTheWholeNumber() {
        configureSender(SMS_PROVIDER, "Text message");
        createUser("masked", NUMBER);

        login("masked");

        assertThat(driver.driver().getPageSource(), not(containsString(NUMBER)));
        assertThat(driver.driver().getPageSource(), containsString("23"));
    }

    @Test
    public void refusesAWrongCode() {
        configureSender(SMS_PROVIDER, "Text message");
        createUser("wrong-code", NUMBER);

        login("wrong-code");
        verifyPhoneNumberPage.submit(wrong(codeFromLastMessage()));

        assertThat(driver.driver().getPageSource(), containsString("Invalid verification code"));
    }

    @Test
    public void firesEvents() {
        configureSender(SMS_PROVIDER, "Text message");
        String userId = createUser("events", NUMBER);

        login("events");

        EventAssertion.assertSuccess(events.poll())
                .type(EventType.SEND_VERIFY_PHONE_NUMBER)
                .details(Details.USERNAME, "events")
                .userId(userId);

        String code = codeFromLastMessage();
        verifyPhoneNumberPage.submit(wrong(code));

        EventAssertion.assertError(events.poll())
                .type(EventType.VERIFY_PHONE_NUMBER_ERROR)
                .error(Errors.INVALID_CODE)
                .details(Details.USERNAME, "events")
                .userId(userId);

        verifyPhoneNumberPage.submit(code);

        EventAssertion.assertSuccess(events.poll())
                .type(EventType.VERIFY_PHONE_NUMBER)
                .details(Details.USERNAME, "events")
                .userId(userId);
    }

    @Test
    public void stopsAcceptingGuesses() {
        configureSender(SMS_PROVIDER, "Text message");
        String userId = createUser("exhausted", NUMBER);
        configureAction(Map.of("maxAttempts", "2"));

        login("exhausted");
        String code = codeFromLastMessage();

        verifyPhoneNumberPage.submit(wrong(code));
        verifyPhoneNumberPage.submit(wrong(code));
        // the real code, after the guesses are used up
        verifyPhoneNumberPage.submit(code);

        assertThat(driver.driver().getPageSource(), containsString("Invalid verification code"));
        Assertions.assertNull(attribute(userId, "phoneNumberVerified"),
                "a code that ran out of attempts must not verify anything");
    }

    @Test
    public void doesNotPromptUntilItIsADefaultAction() {
        configureSender(SMS_PROVIDER, "Text message");
        // before the user exists: default actions are attached when a user is created
        setAction(true, false);
        createUser("not-default", NUMBER);

        login("not-default");

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(),
                "an enabled action that is not a default action should not stand in the way");
        Assertions.assertNull(lastMessage(), "nothing should have been sent");
    }

    @Test
    public void ignoresAUserWithoutANumber() {
        configureSender(SMS_PROVIDER, "Text message");
        createUser("no-number", null);

        login("no-number");

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(),
                "a user with no number should simply log in");
        Assertions.assertNull(lastMessage(), "nothing should have been sent");
    }

    @Test
    public void ignoresAVerifiedUser() {
        configureSender(SMS_PROVIDER, "Text message");
        String userId = createUser("already", NUMBER);
        updateAttributes(userId, Map.of("phoneNumber", List.of(NUMBER), "phoneNumberVerified", List.of("true")));

        login("already");

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
        Assertions.assertNull(lastMessage(), "nothing should have been sent");
    }

    @Test
    public void saysSoWhenNothingCanSend() {
        createUser("no-sender", NUMBER);

        login("no-sender");

        assertThat(driver.driver().getPageSource(), containsString("not available"));
        Assertions.assertNull(lastMessage());
    }

    @Test
    public void reportsAFailureToSend() {
        configureSender(SMS_PROVIDER, "Text message", Map.of("apiKey", "k", "fail", "true"));
        String userId = createUser("failing", NUMBER);

        login("failing");

        EventAssertion.assertError(events.poll())
                .type(EventType.SEND_VERIFY_PHONE_NUMBER_ERROR)
                .error(Errors.PHONE_MESSAGE_SEND_FAILED)
                .details(Details.USERNAME, "failing")
                .userId(userId);

        errorPage.assertCurrent();
        assertThat(errorPage.getError(), is("Failed to send the code, please try again later."));
        Assertions.assertNull(lastMessage());
        Assertions.assertNull(attribute(userId, "phoneNumberVerified"));
    }

    @Test
    public void offersEverySenderTheRealmHas() {
        configureSender(SMS_PROVIDER, "Text message");
        configureSender(VOICE_PROVIDER, "Phone call");
        createUser("choice", NUMBER);

        login("choice");

        Assertions.assertTrue(verifyPhoneNumberPage.isSenderChoiceDisplayed(), "the choice should be offered");
        assertThat(driver.driver().getPageSource(), containsString("Text message"));
        assertThat(driver.driver().getPageSource(), containsString("Phone call"));
        Assertions.assertEquals(0, messageCount(), "nothing should be sent before a sender is chosen");

        verifyPhoneNumberPage.send();
        Assertions.assertEquals(1, messageCount());
    }

    @Test
    public void offersNoChoiceWhenThereIsOnlyOne() {
        configureSender(SMS_PROVIDER, "Text message");
        createUser("single", NUMBER);

        login("single");

        Assertions.assertFalse(verifyPhoneNumberPage.isSenderChoiceDisplayed());
    }

    @Test
    public void refusesASenderWithNoSuchProvider() {
        configureSender(SMS_PROVIDER, "Text message");
        ComponentRepresentation stale = new ComponentRepresentation();
        stale.setName("Gone");
        stale.setProviderId("no-such-sender");
        stale.setProviderType(SENDER_TYPE);
        stale.setParentId(realm.getId());
        // created directly, the way a component left behind by an undeployed provider looks
        try (Response response = realm.admin().components().add(stale)) {
            Assertions.assertEquals(400, response.getStatus(),
                    "a component naming a provider that is not deployed cannot be created");
        }

        // one left behind by a provider removed later is skipped by PhoneMessageSenders
        createUser("stale", NUMBER);
        login("stale");

        Assertions.assertFalse(verifyPhoneNumberPage.isSenderChoiceDisplayed(),
                "only the usable sender is configured, so there is nothing to choose between");
    }

    @Test
    public void doesNotSendAgainWhenThePromptIsReloaded() {
        // a user who refreshes, or comes back to the tab, must not be charged another message
        configureSender(SMS_PROVIDER, "Text message");
        createUser("refresh", NUMBER);

        login("refresh");
        String code = codeFromLastMessage();
        int sent = messageCount();

        driver.driver().navigate().refresh();

        Assertions.assertEquals(sent, messageCount(), "reloading should not send another message");
        verifyPhoneNumberPage.submit(code);
        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(),
                "the code from before the reload should still work");
    }

    @Test
    public void sendsAnotherCodeWhenAsked() {
        configureSender(SMS_PROVIDER, "Text message");
        configureAction(Map.of("resendCooldownSeconds", "0"));
        createUser("resend", NUMBER);

        login("resend");
        Assertions.assertEquals(1, messageCount());

        verifyPhoneNumberPage.resend();

        Assertions.assertEquals(2, messageCount(), "a new code should have been sent");

        verifyPhoneNumberPage.submit(codeFromLastMessage());
        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(), "the new code should work");
    }

    @Test
    public void keepsTheCooldownWhenSendingFailed() {
        configureSender(SMS_PROVIDER, "Text message", Map.of("apiKey", "k", "failAfterSending", "true"));
        configureAction(Map.of("resendCooldownSeconds", "600"));
        createUser("failed-cooldown", NUMBER);

        login("failed-cooldown");
        errorPage.assertCurrent();
        Assertions.assertEquals(1, messageCount());

        login("failed-cooldown");

        verifyPhoneNumberPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertEquals(1, messageCount(), "a failed send must not lift the cooldown");
    }

    @Test
    public void holdsOffAResend() {
        configureSender(SMS_PROVIDER, "Text message");
        configureAction(Map.of("resendCooldownSeconds", "600"));
        createUser("cooldown", NUMBER);

        login("cooldown");
        verifyPhoneNumberPage.resend();

        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertEquals(1, messageCount(), "no second message should have been sent");
    }

    @Test
    public void holdsOffASecondLogin() {
        configureSender(SMS_PROVIDER, "Text message");
        configureAction(Map.of("resendCooldownSeconds", "600"));
        createUser("second-login", NUMBER);

        login("second-login");
        verifyPhoneNumberPage.assertCurrent();
        Assertions.assertEquals(1, messageCount());

        login("second-login");

        verifyPhoneNumberPage.assertCurrent();
        assertThat(driver.driver().getPageSource(), containsString("must wait"));
        Assertions.assertFalse(verifyPhoneNumberPage.isCodeInputDisplayed(), "no code was sent in this login");
        Assertions.assertEquals(1, messageCount(), "a new login must not send within the cooldown");
    }

    @Test
    public void sendsNothingUntilAskedWhenTheApplicationStartedIt() {
        configureSender(SMS_PROVIDER, "Text message");
        setAction(true, false);
        String userId = createUser("aia", NUMBER);

        oauth.loginForm().kcAction(UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name()).open();
        loginPage.fillLogin("aia", PASSWORD);
        loginPage.submit();

        verifyPhoneNumberPage.assertCurrent();
        Assertions.assertFalse(verifyPhoneNumberPage.isCodeInputDisplayed());
        Assertions.assertEquals(0, messageCount(), "nothing should be sent before the user asks");

        verifyPhoneNumberPage.send();

        Assertions.assertEquals(1, messageCount());
        Assertions.assertTrue(verifyPhoneNumberPage.isCodeInputDisplayed());
        verifyPhoneNumberPage.submit(codeFromLastMessage());

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess());
        Assertions.assertEquals("true", attribute(userId, "phoneNumberVerified"));
    }

    @Test
    public void canBeCanceledWhenTheApplicationStartedIt() {
        configureSender(SMS_PROVIDER, "Text message");
        // before the user exists: default actions are attached when a user is created
        setAction(true, false);
        String userId = createUser("cancels", NUMBER);

        oauth.loginForm().kcAction(UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name()).open();
        loginPage.fillLogin("cancels", PASSWORD);
        loginPage.submit();

        Assertions.assertTrue(verifyPhoneNumberPage.isCancelDisplayed(), "a cancel button should be offered");
        verifyPhoneNumberPage.cancel();

        Assertions.assertTrue(oauth.parseLoginResponse().isSuccess(),
                "canceling should return the user to the application");
        Assertions.assertNull(attribute(userId, "phoneNumberVerified"),
                "canceling must not verify anything");
    }

    @Test
    public void keepsTheStoredCredentialWhenTheFormIsSavedAgain() {
        // saving the form again sends the mask back; what the sender is handed when it sends
        // is the only way to tell the stored key from the mask
        String id = configureSender(SMS_PROVIDER, "Text message");

        ComponentRepresentation masked = realm.admin().components().component(id).toRepresentation();
        masked.setName("Renamed");
        realm.admin().components().component(id).update(masked);

        createUser("resaved", NUMBER);
        login("resaved");

        Assertions.assertEquals("secret-key", lastMessage().get("apiKey"),
                "the sender should still hold the credential it was configured with");
    }

    @Test
    public void usesARealmLocalizationOverride() {
        configureSender(SMS_PROVIDER, "Text message");
        realm.admin().localization().saveRealmLocalizationText("en", "phoneVerificationMessage",
                "Your one-time code is {0}");
        realm.admin().toRepresentation().setInternationalizationEnabled(true);
        createUser("localized", NUMBER);

        login("localized");

        assertThat(lastMessage().get("text"), containsString("Your one-time code is"));
    }

    private void login(String username) {
        oauth.openLoginForm();
        loginPage.fillLogin(username, PASSWORD);
        loginPage.submit();
    }

    private String wrong(String code) {
        return (code.startsWith("0") ? "1" : "0") + code.substring(1);
    }

    private String codeFromLastMessage() {
        Map<String, String> sent = lastMessage();
        Assertions.assertNotNull(sent, "a message should have been sent");
        return sent.get("text").replaceAll("\\D+", "").substring(0, 6);
    }

    private int messageCount() {
        return messages().size();
    }

    private Map<String, String> lastMessage() {
        List<Map<String, String>> sent = messages();
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    private List<Map<String, String>> messages() {
        try {
            HttpResponse response = httpClient.execute(new HttpGet(realm.getBaseUrl() + "/test-phone-messages"));
            return JsonSerialization.readValue(response.getEntity().getContent(), new TypeReference<>() { });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void forgetMessages() {
        try {
            httpClient.execute(new HttpDelete(realm.getBaseUrl() + "/test-phone-messages"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String configureSender(String providerId, String name) {
        return configureSender(providerId, name, Map.of("apiKey", "secret-key"));
    }

    private String configureSender(String providerId, String name, Map<String, String> config) {
        ComponentRepresentation component = new ComponentRepresentation();
        component.setName(name);
        component.setProviderId(providerId);
        component.setProviderType(SENDER_TYPE);
        component.setParentId(realm.getId());
        component.setConfig(new MultivaluedHashMap<>());
        config.forEach((key, value) -> component.getConfig().putSingle(key, value));
        return ApiUtil.getCreatedId(realm.admin().components().add(component));
    }

    private void configureAction(Map<String, String> config) {
        RequiredActionConfigRepresentation representation = new RequiredActionConfigRepresentation();
        representation.setConfig(config);
        realm.admin().flows().updateRequiredActionConfig(UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name(),
                representation);
    }

    private void setAction(boolean enabled, boolean defaultAction) {
        String alias = UserModel.RequiredAction.VERIFY_PHONE_NUMBER.name();

        if (realm.admin().flows().getRequiredActions().stream().noneMatch(a -> alias.equals(a.getAlias()))) {
            // a realm that predates the action has it among the unregistered ones, exactly as
            // an administrator would find it in the console
            RequiredActionProviderSimpleRepresentation unregistered = realm.admin().flows()
                    .getUnregisteredRequiredActions().stream()
                    .filter(a -> alias.equals(a.getProviderId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("VERIFY_PHONE_NUMBER is not available at all"));
            realm.admin().flows().registerRequiredAction(unregistered);
        }

        RequiredActionProviderRepresentation action = realm.admin().flows().getRequiredAction(alias);
        action.setEnabled(enabled);
        action.setDefaultAction(defaultAction);
        realm.admin().flows().updateRequiredAction(alias, action);
    }

    private String createUser(String username, String number) {
        // a complete profile, so that VERIFY_PROFILE does not step in front of the prompt
        // this test is about
        UserBuilder user = UserBuilder.create(username).enabled(true).password(PASSWORD)
                .email(username + "@localhost").emailVerified(true).name("Test", "User");
        if (number != null) {
            user.attribute("phoneNumber", number);
        }
        return ApiUtil.getCreatedId(realm.admin().users().create(user.build()));
    }

    private void updateAttributes(String userId, Map<String, List<String>> attributes) {
        UserRepresentation user = realm.admin().users().get(userId).toRepresentation();
        user.setAttributes(attributes);
        realm.admin().users().get(userId).update(user);
    }

    private String attribute(String userId, String name) {
        Map<String, List<String>> attributes = realm.admin().users().get(userId).toRepresentation().getAttributes();
        if (attributes == null) {
            return null;
        }
        List<String> values = attributes.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
