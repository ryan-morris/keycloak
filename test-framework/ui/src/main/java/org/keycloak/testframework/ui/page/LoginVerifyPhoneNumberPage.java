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

package org.keycloak.testframework.ui.page;

import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.ui.Select;

public class LoginVerifyPhoneNumberPage extends AbstractLoginPage {

    @FindBy(id = "code")
    private WebElement codeInput;

    @FindBy(id = "kc-submit")
    private WebElement submitButton;

    @FindBy(id = "kc-send")
    private WebElement sendButton;

    @FindBy(id = "kc-resend")
    private WebElement resendButton;

    @FindBy(name = "cancel-aia")
    private WebElement cancelAIAButton;

    public LoginVerifyPhoneNumberPage(ManagedWebDriver driver) {
        super(driver);
    }

    public void submit(String code) {
        codeInput.clear();
        codeInput.sendKeys(code);
        submitButton.click();
    }

    public void send() {
        sendButton.click();
    }

    public void resend() {
        resendButton.click();
    }

    public void cancel() {
        cancelAIAButton.click();
    }

    public boolean isCancelDisplayed() {
        return isDisplayed(By.name("cancel-aia"));
    }

    public boolean isCodeInputDisplayed() {
        return isDisplayed(By.id("code"));
    }

    public boolean isSenderChoiceDisplayed() {
        return isDisplayed(By.id("senderId"));
    }

    public void selectSender(String name) {
        new Select(driver.findElement(By.id("senderId"))).selectByVisibleText(name);
    }

    private boolean isDisplayed(By locator) {
        try {
            return driver.findElement(locator).isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    @Override
    public String getExpectedPageId() {
        return "login-login-verify-phone-number";
    }
}
