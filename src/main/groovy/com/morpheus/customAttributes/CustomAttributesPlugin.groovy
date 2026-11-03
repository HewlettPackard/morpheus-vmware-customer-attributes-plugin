/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.morpheus.customAttributes

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer
import groovy.util.logging.Slf4j

/**
 * Entry point for the Custom Attributes plugin.
 *
 * One plugin provides the Custom Attributes tab on both the instance page and the server page.
 * Plugin settings (vCenter fallback credentials) are stored encrypted by Morpheus and
 * surfaced in Admin → Plugins → Custom Attributes Plugin → Settings.
 *
 * Route prefix: /plugin/customAttributes/
 */
@Slf4j
class CustomAttributesPlugin extends Plugin {

    private CustomAttributesInstanceTabProvider instanceTabProvider
    private CustomAttributesServerTabProvider serverTabProvider

    @Override
    String getCode() {
        return 'customAttributes'
    }

    @Override
    void initialize() {
        this.setName("Custom Attributes Plugin")
        this.setDescription("Manage vCenter VM custom attributes directly from the Morpheus instance and server tabs")
        this.setAuthor("LYB Platform Team")

        // ---------------------------------------------------------------
        // Plugin settings
        // The vCenter host and credentials are taken from the Morpheus cloud that owns
        // the instance. The user name and password below are only used for a cloud that
        // has no usable credentials of its own, and are always sent to that cloud's host.
        // ---------------------------------------------------------------
        this.settings << new OptionType(
            name        : 'vCenter Username',
            code        : 'customAttributes-vcenter-user',
            fieldName   : 'vCenterUser',
            displayOrder: 0,
            fieldLabel  : 'vCenter Username (fallback)',
            helpText    : 'Optional. Used only when a vCenter cloud has no credentials of its own. Sent only to that cloud\'s vCenter host.',
            required    : false,
            inputType   : OptionType.InputType.TEXT
        )

        this.settings << new OptionType(
            name        : 'vCenter Password',
            code        : 'customAttributes-vcenter-password',
            fieldName   : 'vCenterPassword',
            displayOrder: 1,
            fieldLabel  : 'vCenter Password (fallback)',
            helpText    : 'Optional. Used only when a vCenter cloud has no credentials of its own. Sent only to that cloud\'s vCenter host.',
            required    : false,
            inputType   : OptionType.InputType.PASSWORD
        )

        this.settings << new OptionType(
            name        : 'Allow untrusted vCenter certificate',
            code        : 'customAttributes-allow-untrusted-cert',
            fieldName   : 'allowUntrustedCert',
            displayOrder: 2,
            fieldLabel  : 'Allow untrusted vCenter certificate (insecure)',
            helpText    : 'Off by default. When off, the vCenter certificate must be trusted by the Morpheus JVM and match the host name. Turn on only if the vCenter certificate cannot be trusted, since it allows an attacker on the network to impersonate vCenter.',
            required    : false,
            inputType   : OptionType.InputType.CHECKBOX
        )

        // ---------------------------------------------------------------
        // Permissions
        // ---------------------------------------------------------------
        this.setPermissions([
            Permission.build('Custom Attributes Tab', 'customAttributes-tab',
                [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        ])

        // ---------------------------------------------------------------
        // Register tab providers + controller
        // ---------------------------------------------------------------
        instanceTabProvider = new CustomAttributesInstanceTabProvider(this, this.morpheus)
        this.registerProvider(instanceTabProvider)
        serverTabProvider = new CustomAttributesServerTabProvider(this, this.morpheus)
        this.registerProvider(serverTabProvider)

        // Set a non-null renderer so hasCustomRender() returns true.
        // This prevents PluginManager.registerPlugin() from calling
        // renderer.addTemplateLoader() which throws UnsupportedOperationException
        // on Morpheus 8.1.2 due to an immutable list in DynamicTemplateLoader.
        // The tab provider renders HTML directly; the renderer field is unused.
        this.renderer = new HandlebarsRenderer()

        // Register controller (routes at /plugin/customAttributes/*)
        // Uses session-cookie auth — no Bearer token required from browser
        this.controllers.add(new CustomAttributesController(this, this.morpheus))
        log.debug("CustomAttributesPlugin: controller registered, routes at /plugin/customAttributes/*")
    }

    @Override
    void onDestroy() {
        // nothing to clean up
    }
}

