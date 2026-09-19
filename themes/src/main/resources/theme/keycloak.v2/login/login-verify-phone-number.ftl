<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
<!-- template: login-verify-phone-number.ftl -->
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
                <@field.input name="code" label=msg("phoneVerifyCodeLabel") autocomplete="one-time-code" autofocus=true />
            </#if>

            <#if senders?? && senders?size gt 1>
                <@field.group name="senderId" label=msg("phoneVerifySenderLabel")>
                    <div class="${properties.kcInputClass!}">
                        <select id="senderId" name="senderId">
                            <#list senders as sender>
                                <option value="${sender.id}"<#if selectedSender == sender.id> selected</#if>>${msg(sender.displayName)}</option>
                            </#list>
                        </select>
                        <span class="${properties.kcFormControlUtilClass}">
                            <span class="${properties.kcFormControlToggleIcon!}">
                                <svg class="pf-v5-svg" viewBox="0 0 320 512" fill="currentColor" aria-hidden="true" role="img" width="1em" height="1em">
                                    <path d="M31.3 192h257.3c17.8 0 26.7 21.5 14.1 34.1L174.1 354.8c-7.8 7.8-20.5 7.8-28.3 0L17.2 226.1C4.6 213.5 13.5 192 31.3 192z"></path>
                                </svg>
                            </span>
                        </span>
                    </div>
                </@field.group>
            </#if>

            <@buttons.actionGroup>
                <#if codeSent>
                    <@buttons.button id="kc-submit" label="doSubmit"/>
                    <@buttons.button id="kc-resend" label="phoneVerifyResend" type="secondary" name="resend" value="true"/>
                <#else>
                    <@buttons.button id="kc-send" label="doSendCode" name="resend" value="true"/>
                </#if>
                <#if isAppInitiatedAction??>
                    <@buttons.button id="kc-cancel" label="doCancel" type="secondary" name="cancel-aia" value="true"/>
                </#if>
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
