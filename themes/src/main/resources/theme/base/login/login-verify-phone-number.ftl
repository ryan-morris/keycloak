<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true; section>
    <#if section = "header">
        ${msg("phoneVerifyTitle")}
    <#elseif section = "form">
        <p class="instruction">
            <#if codeSent>
                ${msg("phoneVerifyInstruction", phoneNumber)}
            <#else>
                ${msg("phoneVerifySendInstruction", phoneNumber)}
            </#if>
        </p>

        <form id="kc-verify-phone-number-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <#if codeSent>
                <input type="hidden" id="generation" name="generation" value="${generation!''}" />
                <div class="${properties.kcFormGroupClass!}">
                    <div class="${properties.kcLabelWrapperClass!}">
                        <label for="code" class="${properties.kcLabelClass!}">${msg("phoneVerifyCodeLabel")}</label>
                    </div>
                    <div class="${properties.kcInputWrapperClass!}">
                        <input type="text" id="code" name="code" class="${properties.kcInputClass!}"
                               autocomplete="one-time-code" inputmode="numeric" autofocus
                               dir="ltr" />
                    </div>
                </div>
            </#if>

            <#if senders?? && senders?size gt 1>
                <div class="${properties.kcFormGroupClass!}">
                    <div class="${properties.kcLabelWrapperClass!}">
                        <label for="senderId" class="${properties.kcLabelClass!}">${msg("phoneVerifySenderLabel")}</label>
                    </div>
                    <div class="${properties.kcInputWrapperClass!}">
                        <select id="senderId" name="senderId" class="${properties.kcInputClass!}">
                            <#list senders as sender>
                                <option value="${sender.id}"<#if selectedSender == sender.id> selected</#if>>${msg(sender.displayName)}</option>
                            </#list>
                        </select>
                    </div>
                </div>
            </#if>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <#if codeSent>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                type="submit" id="kc-submit">${msg("doSubmit")}</button>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}"
                                type="submit" id="kc-resend" name="resend" value="true" formnovalidate>${msg("phoneVerifyResend")}</button>
                    <#else>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                type="submit" id="kc-send" name="resend" value="true">${msg("doSendCode")}</button>
                    </#if>
                    <#if isAppInitiatedAction??>
                        <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}"
                                type="submit" id="kc-cancel" name="cancel-aia" value="true" formnovalidate>${msg("doCancel")}</button>
                    </#if>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
