package com.morpheus.customAttributes

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Endpoints for vCenter custom attributes on an instance or a server.
 *
 *   GET|POST /plugin/customAttributes/vmData     instanceId, target                       (read access)
 *   POST     /plugin/customAttributes/setAttr    instanceId|serverId, target, name, value, type (Global or VirtualMachine, for a new attribute)
 *   POST     /plugin/customAttributes/clearAttr  instanceId|serverId, target, name
 *
 * Exactly one of instanceId or serverId is given. For an instance with several VMs, target is required:
 * a server id of that instance (that VM only) or 'all'. A server id means that VM only.
 * Every request must carry the X-Requested-With header and, when the browser sends an Origin or Referer,
 * it must match this host. Changes need 'full' access on the customAttributes-tab permission and the
 * caller must belong to the object's account (or the master account). The VMs and the vCenter are
 * resolved on the server; nothing that identifies a cloud or host is accepted from the client.
 * A VM that fails does not stop the others; each is reported.
 */
@Slf4j
class CustomAttributesController implements PluginController {

    static final String PERMISSION_CODE = 'customAttributes-tab'

    Plugin plugin
    MorpheusContext morpheus

    CustomAttributesController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    final String code = 'customAttributes-controller'
    final String name = 'Custom Attributes Controller'

    @Override String getCode() { return code }
    @Override String getName() { return name }

    @Override
    List<Route> getRoutes() {
        [
            Route.build('customAttributes/vmData',     'vmData',    Permission.build(PERMISSION_CODE, 'read')),
            Route.build('/customAttributes/vmData',    'vmData',    Permission.build(PERMISSION_CODE, 'read')),
            Route.build('customAttributes/setAttr',    'setAttr',   Permission.build(PERMISSION_CODE, 'full')),
            Route.build('/customAttributes/setAttr',   'setAttr',   Permission.build(PERMISSION_CODE, 'full')),
            Route.build('customAttributes/clearAttr',  'clearAttr', Permission.build(PERMISSION_CODE, 'full')),
            Route.build('/customAttributes/clearAttr', 'clearAttr', Permission.build(PERMISSION_CODE, 'full')),
        ]
    }

    def setAttr(ViewModel<Map> model) {
        return handle(model, true)
    }

    def clearAttr(ViewModel<Map> model) {
        return handle(model, false)
    }

    /** Attributes of the selected VM (the primary VM for 'all'), for the instance tab's VM picker. */
    def vmData(ViewModel<Map> model) {
        String ref = SecurityUtil.newRef()
        Long logUserId = null
        String logObject = null
        try {
            String requestError = SecurityUtil.checkRequest(model?.request)
            if (requestError) {
                log.warn("CustomAttributes action=vmData outcome=rejected reason='${requestError}'")
                return respond(false, requestError)
            }
            User user = model.user
            logUserId = user?.id
            if (user == null) {
                return respond(false, 'Not authenticated')
            }
            if (!SecurityUtil.hasRead(user, PERMISSION_CODE)) {
                log.warn("CustomAttributes action=vmData outcome=denied user=${user.id} reason='needs ${PERMISSION_CODE}'")
                return respond(false, 'Permission denied')
            }

            Long instanceId = SecurityUtil.parseId(model.request.getParameter('instanceId'))
            if (instanceId == null) {
                return respond(false, 'Invalid request parameters')
            }
            logObject = "instance=${instanceId}".toString()
            VmTargets.Resolved resolved = resolveTargets(user, 'vmData', logObject, instanceId, null, model.request.getParameter('target'))

            VCenterCredentials creds = VCenterCredentials.resolve(morpheus, plugin, resolved.cloudId)
            if (creds == null) {
                return respond(false, 'vCenter is not available for this cloud')
            }
            Map fetched = CustomAttributesTabRenderer.fetchAttributes(creds, resolved.primary)
            List attrs = (fetched.attributes as List).collect { Map a ->
                [key: a.key, name: a.name, value: a.value ?: '', type: a.type ?: 'VirtualMachine']
            }
            return HTMLResponse.success(SecurityUtil.jsonForScript([
                success: true, certUntrusted: fetched.certUntrusted, vmName: resolved.primary.name, attributes: attrs
            ]))
        } catch (IllegalStateException e) {
            log.warn("CustomAttributes action=vmData outcome=refused ref=${ref} user=${logUserId} ${logObject} reason='${e.message}'")
            return respond(false, e.message)
        } catch (Exception e) {
            log.error("CustomAttributes action=vmData outcome=failed ref=${ref} user=${logUserId} ${logObject}", e)
            return respond(false, "The operation failed (reference ${ref})".toString())
        }
    }

    private HTMLResponse handle(ViewModel<Map> model, boolean isSet) {
        String action = isSet ? 'setAttr' : 'clearAttr'
        String ref = SecurityUtil.newRef()
        Long logUserId = null
        String logObject = null
        VCenterSoapClient client = null
        try {
            String requestError = SecurityUtil.checkRequest(model?.request)
            if (requestError) {
                log.warn("CustomAttributes action=${action} outcome=rejected reason='${requestError}'")
                return respond(false, requestError)
            }

            User user = model.user
            logUserId = user?.id
            if (user == null) {
                log.warn("CustomAttributes action=${action} outcome=rejected reason='no authenticated user'")
                return respond(false, 'Not authenticated')
            }
            if (!SecurityUtil.hasFull(user, PERMISSION_CODE)) {
                log.warn("CustomAttributes action=${action} outcome=denied user=${user.id} reason='needs full ${PERMISSION_CODE}'")
                return respond(false, 'Permission denied')
            }

            String instanceParam = model.request.getParameter('instanceId')
            String serverParam = model.request.getParameter('serverId')
            boolean hasInstance = instanceParam != null && !instanceParam.isEmpty()
            boolean hasServer = serverParam != null && !serverParam.isEmpty()
            Long instanceId = hasInstance ? SecurityUtil.parseId(instanceParam) : null
            Long serverId = hasServer ? SecurityUtil.parseId(serverParam) : null
            String attrName = SecurityUtil.cleanAttrName(model.request.getParameter('name'))
            String attrValue = isSet ? SecurityUtil.cleanAttrValue(model.request.getParameter('value')) : ''
            String attrType = isSet ? SecurityUtil.cleanAttrType(model.request.getParameter('type')) : 'VirtualMachine'
            // Exactly one of instanceId / serverId must be given, and it must be a valid id
            boolean idOk = (hasInstance != hasServer) && (hasInstance ? instanceId != null : serverId != null)
            if (!idOk || attrName == null || attrValue == null || attrType == null) {
                return respond(false, 'Invalid request parameters')
            }
            logObject = hasInstance ? "instance=${instanceId}".toString() : "server=${serverId}".toString()

            VmTargets.Resolved resolved = resolveTargets(user, action, logObject, instanceId, serverId, model.request.getParameter('target'))

            VCenterCredentials creds = VCenterCredentials.resolve(morpheus, plugin, resolved.cloudId)
            if (creds == null) {
                return respond(false, 'vCenter is not available for this cloud')
            }

            client = new VCenterSoapClient(creds.host, creds.user, creds.password, creds.allowUntrusted)
            client.login()

            // One VM failing does not stop the others
            List<String> failed = []
            int done = 0
            resolved.targets.each { VmTargets.Target t ->
                try {
                    String vmMor = client.verifyVmMor(t.vmMor)
                    if (!vmMor) {
                        failed.add("${t.name}: VM not found in vCenter".toString())
                        return
                    }
                    if (isSet) {
                        client.setField(vmMor, attrName, attrValue, attrType)
                    } else {
                        client.clearField(vmMor, attrName)
                    }
                    done++
                    log.info("AUDIT customAttributes action=${action} outcome=success user=${user.id} ${logObject} vm=${t.serverId} attribute='${attrName}'${isSet ? " type=${attrType}" : ''}")
                } catch (Exception e) {
                    String vmRef = SecurityUtil.newRef()
                    log.error("CustomAttributes action=${action} outcome=failed ref=${vmRef} user=${user.id} ${logObject} vm=${t.serverId}", e)
                    failed.add("${t.name}: failed (reference ${vmRef})".toString())
                }
            }

            int total = resolved.targets.size()
            String doneMsg = isSet ? 'Attribute saved' : 'Attribute cleared'
            if (failed.isEmpty()) {
                return respond(true, total > 1 ? "${doneMsg} on all ${total} VMs".toString() : doneMsg)
            }
            return respond(false, "${doneMsg} on ${done} of ${total} VMs. Failed: ${failed.join('; ')}".toString())
        } catch (IllegalStateException e) {
            log.warn("CustomAttributes action=${action} outcome=refused ref=${ref} user=${logUserId} ${logObject} reason='${e.message}'")
            return respond(false, e.message)
        } catch (Exception e) {
            log.error("CustomAttributes action=${action} outcome=failed ref=${ref} user=${logUserId} ${logObject}", e)
            return respond(false, "The operation failed (reference ${ref})".toString())
        } finally {
            if (client != null) {
                try { client.logout() } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Loads the instance or server, checks the caller may access its account, resolves its VMs and, for an
     * instance, narrows them to the selected target. Throws IllegalStateException with a short public message.
     */
    private VmTargets.Resolved resolveTargets(User user, String action, String logObject, Long instanceId, Long serverId, String target) {
        if (instanceId != null) {
            Instance instance = loadInstance(instanceId)
            if (instance == null || !SecurityUtil.canAccessAccount(user, instance.account)) {
                log.warn("CustomAttributes action=${action} outcome=denied user=${user.id} ${logObject} reason='not found or not accessible'")
                throw new IllegalStateException('Instance not found')
            }
            return VmTargets.select(VmTargets.forInstance(morpheus, instance), target)
        }
        ComputeServer server = loadServer(serverId)
        if (server == null || !SecurityUtil.canAccessAccount(user, server.account)) {
            log.warn("CustomAttributes action=${action} outcome=denied user=${user.id} ${logObject} reason='not found or not accessible'")
            throw new IllegalStateException('Server not found')
        }
        return VmTargets.forServer(morpheus, server)
    }

    private Instance loadInstance(Long instanceId) {
        try {
            return morpheus.async.instance.get(instanceId).blockingGet()
        } catch (Exception ignored) {
            return null
        }
    }

    private ComputeServer loadServer(Long serverId) {
        try {
            return morpheus.async.computeServer.get(serverId).blockingGet()
        } catch (Exception ignored) {
            return null
        }
    }

    private static HTMLResponse respond(boolean success, String msg) {
        return HTMLResponse.success(SecurityUtil.jsonForScript([success: success, msg: msg]))
    }
}
