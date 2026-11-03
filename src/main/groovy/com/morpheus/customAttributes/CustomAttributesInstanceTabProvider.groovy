package com.morpheus.customAttributes

import com.morpheusdata.core.AbstractInstanceTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Instance
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/** "Custom Attributes" tab on the instance page of a vSphere-backed instance. */
@Slf4j
class CustomAttributesInstanceTabProvider extends AbstractInstanceTabProvider {

    protected MorpheusContext morpheusContext
    protected Plugin plugin

    CustomAttributesInstanceTabProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    @Override
    HTMLResponse renderTemplate(Instance instance) {
        return CustomAttributesTabRenderer.render(morpheusContext, plugin, CustomAttributesTabRenderer.SCOPE_INSTANCE,
            instance.id, instance.name) { VmTargets.forInstance(morpheusContext, instance) }
    }

    @Override
    Boolean show(Instance instance, User user, Account account) {
        try {
            // Hidden without at least read access. The level only decides which buttons are drawn; the controller enforces it again.
            String permLevel = SecurityUtil.permissionLevel(user, CustomAttributesController.PERMISSION_CODE)
            if (permLevel == 'none') return false

            Long zoneId = instance.provisionZoneId
            if (!zoneId) return false
            Cloud cloud = morpheusContext.async.cloud.get(zoneId).blockingGet()
            if (!SecurityUtil.isVmwareCloud(cloud)) return false

            CustomAttributesTabRenderer.rememberPermission(permLevel)
            return true
        } catch (Exception e) {
            log.warn("CustomAttributesInstanceTabProvider.show: error for instance ${instance?.id}: ${e.class.simpleName}")
            CustomAttributesTabRenderer.forgetPermission()
            return false
        }
    }

    @Override
    MorpheusContext getMorpheus() { return this.morpheusContext }

    @Override
    Plugin getPlugin() { return this.plugin }

    @Override
    String getCode() { return 'customAttributes-instanceTab' }

    @Override
    String getName() { return 'Custom Attributes' }
}
