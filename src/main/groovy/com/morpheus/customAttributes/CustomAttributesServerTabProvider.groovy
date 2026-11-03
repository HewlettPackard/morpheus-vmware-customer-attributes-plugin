package com.morpheus.customAttributes

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/** "Custom Attributes" tab on the server page of a vSphere VM. Acts on that one VM only. */
@Slf4j
class CustomAttributesServerTabProvider extends AbstractServerTabProvider {

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    CustomAttributesServerTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        return CustomAttributesTabRenderer.render(morpheusContext, plugin, CustomAttributesTabRenderer.SCOPE_SERVER,
            server.id, server.name) { VmTargets.forServer(morpheusContext, server) }
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        try {
            // Fails closed: a user without the permission never sees the tab.
            String permLevel = SecurityUtil.permissionLevel(user, CustomAttributesController.PERMISSION_CODE)
            if (permLevel == 'none') return false

            // Hides hosts and other servers that are not a vCenter VM
            if (!SecurityUtil.isValidVmId(server.externalId?.trim())) return false

            Long cloudId = server.cloud?.id
            if (!cloudId) return false
            Cloud cloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
            if (!SecurityUtil.isVmwareCloud(cloud)) return false

            CustomAttributesTabRenderer.rememberPermission(permLevel)
            return true
        } catch (Exception e) {
            log.warn("CustomAttributesServerTabProvider.show: error for server ${server?.id}: ${e.class.simpleName}")
            CustomAttributesTabRenderer.forgetPermission()
            return false
        }
    }

    @Override
    MorpheusContext getMorpheus() { return this.morpheusContext }

    @Override
    Plugin getPlugin() { return this.plugin }

    @Override
    String getCode() { return 'customAttributes-serverTab' }

    @Override
    String getName() { return 'Custom Attributes' }
}
