package com.morpheus.customAttributes

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Cloud
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * vCenter connection details for one Morpheus cloud, resolved on the server.
 * The host always comes from the cloud itself. Plugin settings can only supply a
 * user name and password for that same host, never a different host.
 */
@Slf4j
final class VCenterCredentials {

    final String host
    final String user
    final String password
    final boolean allowUntrusted

    private VCenterCredentials(String host, String user, String password, boolean allowUntrusted) {
        this.host = host
        this.user = user
        this.password = password
        this.allowUntrusted = allowUntrusted
    }

    /** Returns null when the cloud is missing, is not a VMware cloud, or has no usable host and credentials. */
    static VCenterCredentials resolve(MorpheusContext morpheus, Plugin plugin, Long cloudId) {
        if (cloudId == null) return null
        Cloud cloud
        try {
            cloud = morpheus.async.cloud.get(cloudId).blockingGet()
        } catch (Exception e) {
            log.warn("VCenterCredentials: cloud ${cloudId} could not be loaded: ${e.class.simpleName}")
            return null
        }
        if (!cloud || !SecurityUtil.isVmwareCloud(cloud)) {
            log.warn("VCenterCredentials: cloud ${cloudId} is missing or is not a VMware cloud")
            return null
        }

        Map cfgMap = [:]
        try { cfgMap = cloud.configMap ?: [:] } catch (Exception ignored) {}

        String host = ''
        for (String key in ['serviceUrl', 'apiUrl', 'apiHost', 'host', 'vcenterHost', 'vcenter', 'url']) {
            String candidate = cfgMap[key]?.toString()?.trim() ?: ''
            if (candidate) {
                host = hostOnly(candidate)
                break
            }
        }
        if (!host) host = hostOnly(cloud.serviceUrl ?: '')

        String user = ''
        String pass = ''
        try {
            Map credData = cloud.accountCredentialData ?: [:]
            user = credData['username']?.toString()?.trim() ?: ''
            pass = credData['password']?.toString()?.trim() ?: ''
        } catch (Exception ignored) {}

        if (!user || !pass) {
            try {
                def accountCred = morpheus.async.cloud.loadCredentials(cloudId).blockingGet()
                if (accountCred?.data) {
                    user = accountCred.data['username']?.toString()?.trim() ?: ''
                    pass = accountCred.data['password']?.toString()?.trim() ?: ''
                }
            } catch (Exception ignored) {}
        }
        if (!user || !pass) {
            user = cfgMap['serviceUsername']?.toString()?.trim() ?: cfgMap['username']?.toString()?.trim() ?: ''
            pass = cfgMap['servicePassword']?.toString()?.trim() ?: cfgMap['password']?.toString()?.trim() ?: ''
        }
        if (!user || !pass) {
            user = cloud.serviceUsername?.trim() ?: ''
            pass = cloud.servicePassword?.trim() ?: ''
        }

        Map settings = readSettings(morpheus, plugin)
        if ((!user || !pass) && settings.user && settings.pass) {
            user = settings.user
            pass = settings.pass
        }

        if (!SecurityUtil.isValidHost(host) || !user || !pass) {
            log.warn("VCenterCredentials: cloud ${cloudId} has no usable vCenter host or credentials")
            return null
        }
        return new VCenterCredentials(host, user, pass, settings.allowUntrusted == true)
    }

    private static String hostOnly(String value) {
        return value.replaceAll('^https?://', '').replaceAll('/.*$', '').trim()
    }

    private static Map readSettings(MorpheusContext morpheus, Plugin plugin) {
        String out = ''
        try {
            morpheus.getSettings(plugin).subscribe(
                { outData -> out = outData },
                { err -> log.warn("VCenterCredentials: plugin settings could not be read (${err.class.simpleName})") }
            )
            def json = new JsonSlurper().parseText(out ?: '{}')
            String flag = json.allowUntrustedCert?.toString()?.toLowerCase()
            return [
                user          : json.vCenterUser?.toString()?.trim() ?: '',
                pass          : json.vCenterPassword?.toString()?.trim() ?: '',
                allowUntrusted: flag in ['true', 'on', 'yes', '1']
            ]
        } catch (Exception e) {
            log.warn("VCenterCredentials: plugin settings could not be parsed: ${e.class.simpleName}")
            return [user: '', pass: '', allowUntrusted: false]
        }
    }
}
