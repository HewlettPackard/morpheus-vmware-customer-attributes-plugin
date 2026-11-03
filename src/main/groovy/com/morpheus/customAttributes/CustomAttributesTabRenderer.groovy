package com.morpheus.customAttributes

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HTMLResponse
import groovy.util.logging.Slf4j

/**
 * Builds the Custom Attributes tab for both the instance page and the server page.
 *
 * The attributes shown are those of the primary VM. On an instance with several VMs the tab says so,
 * and states that a saved change is applied to all of them.
 *
 * The HTML is built without Handlebars to avoid the DynamicTemplateLoader#addTemplateLoader
 * UnsupportedOperationException that Morpheus 8.1.2 raises when a plugin has both controllers
 * and HBS templates. CSS and JS are static assets served from 'self'.
 */
@Slf4j
final class CustomAttributesTabRenderer {

    static final String SCOPE_INSTANCE = 'instance'
    static final String SCOPE_SERVER = 'server'

    // Embedded in the page: the /assets/plugin/<name>/ URL changes with the plugin name, so it is not relied on.
    private static final String TAB_CSS = loadResource('/assets/customAttributesTab.css')
    private static final String TAB_JS = loadResource('/assets/customAttributesTab.js')

    // Set by a provider's show(), read by its renderTemplate(); only drives which buttons are drawn.
    private static final ThreadLocal<String> permissionLevel = new ThreadLocal<String>()

    private CustomAttributesTabRenderer() {}

    private static String loadResource(String path) {
        InputStream is = CustomAttributesTabRenderer.getResourceAsStream(path)
        if (is == null) {
            log.error("CustomAttributesTabRenderer: resource ${path} is missing from the plugin jar")
            return ''
        }
        try {
            return new String(is.readAllBytes(), 'UTF-8')
        } finally {
            is.close()
        }
    }

    static void rememberPermission(String level) { permissionLevel.set(level) }

    static void forgetPermission() { permissionLevel.remove() }

    /** Reads every VM attribute definition and the VM's values. Throws IllegalStateException when the VM is not in vCenter. */
    static Map fetchAttributes(VCenterCredentials creds, VmTargets.Target target) {
        VCenterSoapClient client = new VCenterSoapClient(creds.host, creds.user, creds.password, creds.allowUntrusted)
        try {
            client.login()
            boolean untrusted = client.isCertUntrusted()
            String vmMor = client.verifyVmMor(target.vmMor)
            if (!vmMor) {
                throw new IllegalStateException('The VM was not found in vCenter.')
            }
            return [certUntrusted: untrusted, attributes: client.getAllFieldDefinitionsWithValues(vmMor)]
        } finally {
            client.logout()
        }
    }

    static HTMLResponse render(MorpheusContext morpheus, Plugin plugin, String scope, Long objectId, String objectName,
                               Closure<VmTargets.Resolved> resolver) {
        Map payload = [
            scope        : scope,
            objectId     : objectId,
            objectName   : objectName,
            attributes   : [],
            vmNames      : [],
            primaryName  : null,
            error        : null,
            settingsOk   : false,
            certUntrusted: false
        ]

        try {
            VmTargets.Resolved resolved = resolver.call()
            // vCenter host and credentials come from the VM's own cloud, resolved on the server.
            VCenterCredentials creds = VCenterCredentials.resolve(morpheus, plugin, resolved.cloudId)
            if (creds == null) {
                payload.error = 'vCenter credentials are not available for this cloud. ' +
                    'Ensure the vCenter cloud integration has credentials set.'
            } else {
                payload.settingsOk = true
                payload.vmNames = resolved.targets.collect { VmTargets.Target t -> t.name }
                payload.primaryName = resolved.primary.name
                if (scope == SCOPE_INSTANCE && resolved.targets.size() > 1) {
                    // Several VMs: the user picks one, and the data is loaded from vmData; no vCenter call yet.
                    payload.dynamic = true
                    payload.vmOptions = resolved.targets.collect { VmTargets.Target t -> [id: t.serverId, name: t.name] }
                } else {
                    Map fetched = fetchAttributes(creds, resolved.primary)
                    payload.certUntrusted = fetched.certUntrusted
                    payload.attributes = fetched.attributes
                }
            }
        } catch (IllegalStateException ex) {
            log.warn("CustomAttributesTabRenderer: refused for ${scope} ${objectId}: ${ex.message}")
            payload.error = ex.message
        } catch (Exception ex) {
            String ref = SecurityUtil.newRef()
            log.error("CustomAttributesTabRenderer: error fetching attributes for ${scope} ${objectId} (ref ${ref})", ex)
            payload.error = "Failed to retrieve custom attributes (reference ${ref}).".toString()
        }

        payload.canWrite = ((permissionLevel.get() ?: 'read') == 'full')
        permissionLevel.remove()

        return HTMLResponse.success(buildTabHtml(morpheus, payload))
    }

    private static String esc(Object v) {
        if (v == null) return ''
        v.toString()
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
            .replace('"', '&quot;')
            .replace("'", '&#39;')
    }

    private static String buildTabHtml(MorpheusContext morpheus, Map payload) {
        String scope  = esc(payload.scope)
        String oid    = esc(payload.objectId)
        String oname  = esc(payload.objectName)
        boolean hasErr     = payload.error as boolean
        String  errMsg     = payload.error ? esc(payload.error) : ''
        boolean settingsOk = payload.settingsOk as boolean
        boolean canWrite   = payload.canWrite as boolean
        boolean certUntrusted = payload.certUntrusted as boolean
        List    attrs      = (payload.attributes ?: []) as List
        List    attrsWithValues = attrs.findAll { it.value }
        List<String> vmNames = (payload.vmNames ?: []) as List<String>
        boolean dynamic    = payload.dynamic as boolean
        List<Map> vmOptions = (payload.vmOptions ?: []) as List<Map>
        def b = new StringBuilder()
        b.append('<style>\n').append(TAB_CSS).append('\n</style>\n')

        // Every VM attribute (also those without a value) for the edit dialog; encoded so it cannot close the script element
        b.append('<script id="ca-all-attrs-data" type="application/json">')
         .append(SecurityUtil.jsonForScript(attrs.collect { Map a ->
             [key: a.key, name: a.name, value: a.value ?: '', type: a.type ?: 'VirtualMachine']
         }))
         .append('</script>\n')

        b.append('<div id="ca-container" data-scope="').append(scope)
         .append('" data-id="').append(oid)
         .append('" data-name="').append(oname)
         .append('" data-dynamic="').append(dynamic && !hasErr)
         .append('" data-can-write="').append(canWrite && settingsOk).append('">\n')

        if (certUntrusted || (dynamic && !hasErr)) {
            // vCenter reached over HTTPS but the certificate could not be verified (self-signed / untrusted).
            // In picker mode the warning starts hidden and is shown once vCenter has been contacted.
            b.append('  <div id="ca-cert-warning" class="ca-warning-banner"').append(certUntrusted ? '' : ' style="display:none"').append('>')
             .append('<strong>&#9888; vCenter certificate not verified.</strong> ')
             .append('The connection is encrypted (HTTPS) but the certificate is self-signed or untrusted, so the server identity cannot be confirmed. ')
             .append('This is permitted because &ldquo;Allow untrusted vCenter certificate&rdquo; is enabled in the plugin settings.')
             .append('</div>\n')
        }

        if (!hasErr && dynamic) {
            b.append('  <div class="ca-info-banner" id="ca-multi-vm-banner">')
             .append('<strong>This instance has ').append(vmNames.size()).append(' VMs.</strong> ')
             .append('Select the VM to view and change, or choose All VMs to change every VM at once.')
             .append('</div>\n')
            b.append('  <div class="ca-vm-picker"><label class="ca-label" for="ca-vm-select">Virtual machine</label>')
             .append('<select id="ca-vm-select" class="ca-select" data-primary-name="').append(esc(payload.primaryName)).append('">')
             .append('<option value="" selected>Select a VM...</option>')
            vmOptions.each { Map o ->
                b.append('<option value="').append(esc(o.id)).append('">').append(esc(o.name)).append('</option>')
            }
            b.append('<option value="all">All VMs</option></select>')
             .append('<div id="ca-select-note" class="ca-select-note"></div></div>\n')
        }

        if (hasErr) {
            b.append('  <div class="ca-error-banner">')
             .append('<strong>Error:</strong> ').append(errMsg).append('</div>\n')
        } else {
            b.append('  <div class="ca-header"><div class="ca-header-title">Custom Attributes</div></div>\n')

            b.append('  <div class="ca-status-card" id="ca-status-card">\n')
            if (dynamic) {
                b.append('    <div class="ca-empty">Select a VM to view its custom attributes.</div>\n')
            } else if (attrsWithValues) {
                attrsWithValues.each { attr ->
                    b.append('    <div class="ca-attr-row">')
                     .append('<div class="ca-attr-name">').append(esc(attr.name)).append('</div>')
                     .append('<div class="ca-attr-val">').append(esc(attr.value)).append('</div>')
                     .append('</div>\n')
                }
            } else {
                b.append('    <div class="ca-empty">No custom attributes assigned.</div>\n')
            }
            b.append('  </div>\n')

            // Edit is drawn only for users with 'full'; the controller enforces it again on every call.
            if (settingsOk && canWrite) {
                b.append('  <div class="ca-actions">')
                 .append('<button type="button" id="ca-open-edit-btn" class="ca-btn ca-btn-primary"').append(dynamic ? ' disabled' : '').append('>')
                 .append('<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>')
                 .append('Edit</button></div>\n')

                b.append('  <div id="ca-edit-modal-overlay" class="ca-modal-overlay"><div id="ca-edit-modal" class="ca-edit-modal">\n')
                 .append('    <div class="ca-modal-header"><h4 class="ca-modal-title">Edit Custom Attributes</h4>')
                 .append('<button type="button" class="ca-modal-close" id="ca-modal-close" aria-label="Close">&times;</button></div>\n')
                 .append('    <div class="ca-modal-body">\n')
                 .append('      <div class="ca-add-form">\n')
                 .append('        <div class="ca-form-col ca-col-attr"><label class="ca-label" for="ca-new-attr-name">Attribute</label>')
                 .append('<input type="text" id="ca-new-attr-name" class="ca-input" autocomplete="off"/></div>\n')
                 .append('        <div class="ca-form-col ca-col-value"><label class="ca-label" for="ca-new-attr-value">Value</label>')
                 .append('<input type="text" id="ca-new-attr-value" class="ca-input" autocomplete="off"/></div>\n')
                 .append('        <div class="ca-form-col ca-col-type"><label class="ca-label" for="ca-new-attr-type">Type</label>')
                 .append('<select id="ca-new-attr-type" class="ca-select" title="Global attributes apply to every vCenter object type">')
                 .append('<option value="VirtualMachine" selected>Virtual Machine</option><option value="Global">Global</option></select></div>\n')
                 .append('        <div class="ca-form-col ca-col-btn"><label class="ca-label">&nbsp;</label>')
                 .append('<button type="button" class="ca-btn-add" id="ca-add-row-btn">ADD</button></div>\n')
                 .append('      </div>\n')
                 .append('      <div id="ca-status-msg" class="ca-status-msg"></div>\n')
                 .append('      <div class="ca-attr-browser">\n')
                 .append('        <div class="ca-attr-header"><div class="ca-attr-header-name">Attribute</div>')
                 .append('<div class="ca-attr-header-value">Value</div><div class="ca-attr-header-type">Type</div></div>\n')
                 .append('        <div id="ca-attr-list"></div>\n')
                 .append('      </div>\n')
                 .append('    </div>\n')
                 .append('    <div class="ca-modal-footer">')
                 .append('<button type="button" class="ca-btn-cancel" id="ca-cancel-btn">CANCEL</button>')
                 .append('<button type="button" class="ca-btn-save" id="ca-save-btn">SAVE</button></div>\n')
                 .append('  </div></div>\n')
            }
        }

        b.append('</div>\n\n')

        // Inline script needs the CSP nonce on Morpheus 8.x
        String nonce = nonceOf(morpheus)
        b.append('<script')
        if (nonce) b.append(' nonce="').append(esc(nonce)).append('"')
        b.append('>\n').append(TAB_JS).append('\n</script>\n')

        return b.toString()
    }

    /** The per-request CSP nonce Morpheus 8.x embeds in the CSP header. */
    private static String nonceOf(MorpheusContext morpheus) {
        try {
            return morpheus.getWebRequest()?.getNonceToken()?.toString() ?: ''
        } catch (Exception ignored) {
            return ''
        }
    }
}
