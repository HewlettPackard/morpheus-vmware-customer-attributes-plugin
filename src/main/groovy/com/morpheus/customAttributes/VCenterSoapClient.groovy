package com.morpheus.customAttributes

import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HostnameVerifier
import java.nio.charset.StandardCharsets

/**
 * Minimal vSphere vim25 SOAP client.
 *
 * All CRUD operations on VM custom attributes require the vSphere
 * CustomFieldsManager, which is only reachable via the SOAP/vim25 API
 * (the vSphere REST Automation API does not expose it).
 *
 * The client deliberately avoids adding the bulky com.vmware:vsphere-ws-java
 * dependency — plain HTTP + XML is sufficient.
 *
 * Session lifecycle:
 *   login()  →  use getFieldDefinitions / getVmCustomValues / setField etc.  →  logout()
 *
 * All callers should wrap in try/finally to ensure logout() is called.
 */
@Slf4j
class VCenterSoapClient {

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------
    private final String vcHost
    private final String vcUser
    private final String vcPassword
    private final String sdkUrl

    /** When false (default) the JVM trust store and host name verification apply. */
    private final boolean allowUntrusted

    /** Set when allowUntrusted is on, so the tab can warn that the certificate was not verified. */
    private final TlsSupport.TrustOutcome trustOutcome
    private final SSLSocketFactory recordingFactory
    private final HostnameVerifier recordingVerifier

    /** vim25 session cookie returned by Login */
    private String sessionCookie

    /** API version returned by RetrieveServiceContent (e.g. '8.0.2.0') */
    private String apiVersion = '8.0.2.0'

    /** Returns the vCenter API version string (populated after login()). */
    String getApiVersion() { return apiVersion }

    /** MOR values returned by RetrieveServiceContent */
    private String customFieldsManagerRef = 'CustomFieldsManager'
    private String propertyCollectorRef   = 'propertyCollector'
    private String sessionManagerRef      = 'SessionManager'

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    VCenterSoapClient(String vcHost, String vcUser, String vcPassword, boolean allowUntrusted = false) {
        if (!SecurityUtil.isValidHost(vcHost)) {
            throw new IllegalArgumentException('Invalid vCenter host')
        }
        this.vcHost         = vcHost
        this.vcUser         = vcUser
        this.vcPassword     = vcPassword
        this.allowUntrusted = allowUntrusted
        if (allowUntrusted) {
            this.trustOutcome    = new TlsSupport.TrustOutcome()
            this.recordingFactory  = TlsSupport.recordingTrustAllFactory(trustOutcome)
            this.recordingVerifier = TlsSupport.recordingVerifier(trustOutcome)
        } else {
            this.trustOutcome    = null
            this.recordingFactory  = null
            this.recordingVerifier = null
        }
        this.sdkUrl         = "https://${vcHost}/sdk"
        log.debug("VCenterSoapClient: Created client — host='${vcHost}'")
        if (allowUntrusted) log.warn("VCenterSoapClient: certificate verification is disabled for '${vcHost}' (plugin setting allowUntrustedCert)")
    }

    /** Opens an HTTPS connection to vCenter, applying the per-connection TLS policy. */
    private HttpsURLConnection openConn(URL url) {
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection()
        if (allowUntrusted) {
            conn.setSSLSocketFactory(recordingFactory)
            conn.setHostnameVerifier(recordingVerifier)
        } else {
            TlsSupport.apply(conn, false)
        }
        return conn
    }

    /** True when allowUntrusted is on and the vCenter certificate could not be verified (self-signed or host-name mismatch). */
    boolean isCertUntrusted() {
        return allowUntrusted && trustOutcome?.untrusted
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Authenticate and store session cookie. */
    void login() {
        log.debug("VCenterSoapClient: login started")
        // Step 1: discover service content
        log.debug("VCenterSoapClient: Calling RetrieveServiceContent")
        def scXml = soapCall(buildRetrieveServiceContentEnvelope(), null)
        def sc = parseXml(scXml)
        sessionManagerRef      = sc.'**'.find { it.name() == 'sessionManager'      }?.text() ?: 'SessionManager'
        customFieldsManagerRef = sc.'**'.find { it.name() == 'customFieldsManager' }?.text() ?: 'CustomFieldsManager'
        propertyCollectorRef   = sc.'**'.find { it.name() == 'propertyCollector'   }?.text() ?: 'propertyCollector'
        apiVersion             = sc.'**'.find { it.name() == 'apiVersion'           }?.text() ?: '8.0.2.0'
        log.debug("VCenterSoapClient: ServiceContent retrieved, apiVersion='${apiVersion}'")

        // Step 2: login
        log.debug("VCenterSoapClient: Calling Login")
        def loginXml = soapCall(buildLoginEnvelope(vcUser, vcPassword, sessionManagerRef), null)
        log.debug("VCenterSoapClient: session established=${sessionCookie != null}")
    }

    /**
     * Confirms that vCenter has a VirtualMachine with this managed object reference (e.g. "vm-123").
     * Returns the reference, or null when no such VM exists.
     */
    String verifyVmMor(String vmMor) {
        if (!SecurityUtil.isValidVmId(vmMor)) {
            throw new IllegalArgumentException('Invalid VM identifier')
        }
        String xml
        try {
            xml = soapCall(buildVmExistsEnvelope(vmMor, propertyCollectorRef), sessionCookie)
        } catch (RuntimeException e) {
            if (e.message?.contains('already been deleted')) {
                log.warn("VCenterSoapClient: VM ${vmMor} no longer exists in vCenter")
                return null
            }
            throw e
        }
        boolean exists = parseXml(xml).'**'.any { it.name() == 'propSet' }
        if (!exists) {
            log.warn("VCenterSoapClient: VM ${vmMor} not found in vCenter")
            return null
        }
        return vmMor
    }

    /** Gracefully end the vCenter session. */
    void logout() {
        log.debug("VCenterSoapClient: logout() — sessionCookie=${sessionCookie ? 'present' : 'null'}")
        try {
            if (sessionCookie) {
                soapCall(buildLogoutEnvelope(sessionManagerRef), sessionCookie)
                log.debug("VCenterSoapClient: logged out")
            }
        } catch (Exception e) {
            log.warn("VCenterSoapClient: logout failed (${e.class.simpleName})")
        }
        sessionCookie = null
    }

    /**
     * Returns all custom field definitions with their scope.
     * @return List of [key: int, name: String, type: String] where type is "Global" (no object type)
     *         or the vCenter object type the definition is scoped to, e.g. "VirtualMachine"
     */
    List<Map<String, Object>> getFieldDefinitions() {
        String xml = soapCall(buildRetrieveFieldDefinitionsEnvelope(customFieldsManagerRef, propertyCollectorRef), sessionCookie)
        log.debug("VCenterSoapClient: getFieldDefinitions response received (${xml?.length() ?: 0} chars)")
        GPathResult root = parseXml(xml)

        List<Map<String, Object>> defs = []
        // Find all CustomFieldDef elements anywhere in the response tree.
        // We intentionally avoid filtering on xsi:type attributes because
        // Groovy XmlSlurper resolves @type as the plain 'type' attribute;
        // the namespace-qualified xsi:type is not matched that way.
        root.'**'.findAll { it.name() == 'CustomFieldDef' }.each { fieldNode ->
            def key  = fieldNode.key?.text()?.toInteger()
            def name = fieldNode.name?.text()
            String managedObjectType = fieldNode.managedObjectType?.text()?.trim() ?: ''
            if (key != null && name) {
                defs << [key: key, name: name, type: managedObjectType ?: 'Global']
            }
        }
        log.debug("VCenterSoapClient: ${defs.size()} field definition(s)")
        return defs
    }

    /** Definitions that can hold a value on a VM: Global ones and those scoped to VirtualMachine. */
    List<Map<String, Object>> getVmApplicableFieldDefinitions() {
        return getFieldDefinitions().findAll { Map d -> d.type == 'Global' || d.type == 'VirtualMachine' }
    }

    /**
     * Returns the current custom attribute values for a specific VM.
     * @param vmMor  The VM managed object reference (e.g. "vm-123")
     * @return List of [key: int, value: String]
     */
    List<Map<String, Object>> getVmCustomValues(String vmMor) {
        String xml = soapCall(buildRetrieveVmCustomValuesEnvelope(vmMor, propertyCollectorRef), sessionCookie)
        log.debug("VCenterSoapClient: getVmCustomValues response received (${xml?.length() ?: 0} chars)")
        GPathResult root = parseXml(xml)

        List<Map<String, Object>> values = []
        // vCenter may use CustomFieldValue or CustomFieldStringValue as element names
        root.'**'.findAll { n ->
            (n.name() == 'CustomFieldValue' || n.name() == 'CustomFieldStringValue') && n.key.text()
        }.each { node ->
            def key   = node.key?.text()?.toInteger()
            def value = node.value?.text() ?: ''
            if (key != null) {
                values << [key: key, value: value]
            }
        }
        log.debug("VCenterSoapClient: ${values.size()} value(s) for VM ${vmMor}")
        return values
    }

    /**
     * Returns every custom field definition that applies to VMs together with this VM's current value,
     * including definitions that have no value yet (as vCenter's own edit dialog does).
     * @param vmMor  The VM managed object reference (e.g. "vm-123")
     * @return List of [key: int, name: String, type: String, value: String], sorted by name
     */
    List<Map<String, Object>> getAllFieldDefinitionsWithValues(String vmMor) {
        List<Map<String, Object>> defs = getVmApplicableFieldDefinitions()
        List<Map<String, Object>> vals = getVmCustomValues(vmMor)

        Map<Integer, String> keyToValue = vals.collectEntries { [(it.key): it.value] }

        return defs.collect { Map d ->
            [key: d.key, name: d.name, type: d.type, value: keyToValue.get(d.key) ?: '']
        }.sort { Map a, Map b -> (a.name as String).compareToIgnoreCase(b.name as String) }
    }

    /**
     * Sets a custom attribute on the VM.
     * If the field definition does not exist yet it is created as a VirtualMachine attribute.
     *
     * @param vmMor     VM managed object reference
     * @param fieldName Canonical field name
     * @param value     New value (empty string clears the field)
     */
    void setField(String vmMor, String fieldName, String value) {
        setField(vmMor, fieldName, value, 'VirtualMachine')
    }

    /**
     * @param fieldType "Global" or "VirtualMachine", used only when the definition has to be created
     */
    void setField(String vmMor, String fieldName, String value, String fieldType) {
        int key = getOrCreateFieldKey(fieldName, fieldType)
        soapCall(buildSetFieldEnvelope(customFieldsManagerRef, vmMor, key, value), sessionCookie)
        log.debug("VCenterSoapClient: Set '${fieldName}' on VM ${vmMor}")
    }

    /**
     * Clears a custom attribute (sets value to empty string).
     */
    void clearField(String vmMor, String fieldName) {
        setField(vmMor, fieldName, '')
    }

    /**
     * Permanently removes a custom attribute definition from vCenter.
     * This deletes the field from ALL VMs and removes it from the schema entirely.
     *
     * @param fieldName  The name of the custom attribute to remove
     * @throws RuntimeException if the field is not found or the SOAP call fails
     */
    void removeFieldDefinition(String fieldName) {
        List<Map<String, Object>> defs = getFieldDefinitions()
        Map<String, Object> existing = defs.find { normalise(it.name as String) == normalise(fieldName) }
        if (!existing) {
            throw new RuntimeException("Custom attribute '${fieldName}' not found in vCenter")
        }
        int key = existing.key as int
        soapCall(buildRemoveFieldDefinitionEnvelope(customFieldsManagerRef, key), sessionCookie)
        log.info("VCenterSoapClient: removed field definition '${fieldName}' key=${key}")
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private int getOrCreateFieldKey(String fieldName, String fieldType) {
        List<Map<String, Object>> defs = getVmApplicableFieldDefinitions()
        Map<String, Object> existing = defs.find { normalise(it.name as String) == normalise(fieldName) }
        if (existing) {
            return existing.key as int
        }
        // Create the field definition via SOAP using pyVmomi-compatible envelope format.
        String xml
        try {
            xml = soapCall(buildAddFieldDefinitionEnvelope(customFieldsManagerRef, fieldName, fieldType), sessionCookie)
        } catch (RuntimeException e) {
            // DuplicateName: field exists but was missed by normalised comparison — refresh and find it
            String msg = e.message?.toLowerCase() ?: ''
            if (msg.contains('duplicate') || msg.contains('already exist')) {
                defs = getVmApplicableFieldDefinitions()
                existing = defs.find { normalise(it.name as String) == normalise(fieldName) }
                if (existing) {
                    log.debug("VCenterSoapClient: field '${fieldName}' already exists, key=${existing.key}")
                    return existing.key as int
                }
            }
            throw e
        }
        GPathResult root = parseXml(xml)
        def key = root.'**'.find { it.name() == 'key' }?.text()?.toInteger()
        if (key == null) {
            throw new RuntimeException("Failed to create custom field definition for '${fieldName}'")
        }
        return key
    }

    private static String normalise(String s) {
        s?.replaceAll(/\s/, '')?.toLowerCase() ?: ''
    }

    /**
     * Extracts a human-readable message from a vCenter SOAP fault response.
     */
    private static String parseSoapFaultMessage(String xml) {
        try {
            if (!xml) return null
            GPathResult root = parseXml(xml)
            String priv = root.'**'.find { it.name() == 'privilegeId' }?.text()
            if (priv) return "vCenter permission denied — the vCenter user needs the '${priv}' privilege"
            String fs = root.'**'.find { it.name() == 'faultstring' }?.text()
            if (fs) return "vCenter SOAP fault: ${fs}"
            return null
        } catch (Exception ignored) {
            return null
        }
    }
    // Used for AddFieldDefinition and RemoveFieldDefinition because the SOAP
    // versions of those operations are not resolvable via urn:vim25 (mapped
    // to vim.version.version3) and vCenter rejects version-specific namespaces.
    // -----------------------------------------------------------------------

    /**
     * Creates a vSphere REST API session using Basic auth.
     * Works with vSphere 7.0+ (/api/session).
     */
    private String createRestSession() {
        String encoded = Base64.encoder.encodeToString(
            "${vcUser}:${vcPassword}".getBytes(StandardCharsets.UTF_8))
        URL url = new URL("https://${vcHost}/api/session")
        HttpsURLConnection conn = openConn(url)
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(30000)
        try {
            conn.setRequestMethod('POST')
            conn.setDoOutput(false)
            conn.setRequestProperty('Authorization', "Basic ${encoded}")
            conn.setRequestProperty('Content-Length', '0')
            int status = conn.responseCode
            InputStream is = status < 400 ? conn.inputStream : conn.errorStream
            String response = is.getText(StandardCharsets.UTF_8.name())
            if (status >= 400) {
                log.warn("VCenterSoapClient: REST session failed (HTTP ${status})")
                throw new RuntimeException(
                    "Cannot connect to vSphere REST API (HTTP ${status}). " +
                    "This feature requires vSphere 7.0 or later.")
            }
            // Response is a JSON-quoted string: "\"abc123\"" — strip the outer quotes
            String token = response.trim().replaceAll('^"|"$', '')
            log.debug("VCenterSoapClient: REST session created")
            return token
        } finally {
            conn.disconnect()
        }
    }

    private void deleteRestSession(String token) {
        try {
            URL url = new URL("https://${vcHost}/api/session")
            HttpsURLConnection conn = openConn(url)
            conn.setConnectTimeout(5000)
            conn.setReadTimeout(5000)
            try {
                conn.setRequestMethod('DELETE')
                conn.setRequestProperty('vmware-api-session-id', token)
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (Exception e) {
            log.warn("VCenterSoapClient: REST session close failed (${e.class.simpleName})")
        }
    }

    /**
     * Creates a VM-scoped custom attribute definition via the vSphere REST API.
     * Returns the field key (id) so it can be used immediately in SetField.
     */
    private int addFieldDefinitionRest(String fieldName) {
        String token = createRestSession()
        try {
            String body = "{\"name\":\"${escapeJson(fieldName)}\",\"object_type\":\"VIRTUAL_MACHINE\"}"
            URL url = new URL("https://${vcHost}/api/vcenter/custom-attributes")
            HttpsURLConnection conn = openConn(url)
            conn.setConnectTimeout(15000)
            conn.setReadTimeout(30000)
            try {
                conn.setRequestMethod('POST')
                conn.setDoOutput(true)
                conn.setRequestProperty('Content-Type', 'application/json')
                conn.setRequestProperty('vmware-api-session-id', token)
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8)
                conn.setRequestProperty('Content-Length', bytes.length.toString())
                conn.outputStream.write(bytes)
                conn.outputStream.flush()
                int status = conn.responseCode
                InputStream is = status < 400 ? conn.inputStream : conn.errorStream
                String response = is.getText(StandardCharsets.UTF_8.name())
                if (status >= 400) {
                    log.warn("VCenterSoapClient: REST addFieldDefinition failed (HTTP ${status})")
                    throw new RuntimeException("Cannot create attribute '${fieldName}' (HTTP ${status})")
                }
                // Response is a plain integer: 103
                int id = response.trim().replaceAll('^"|"$', '') as int
                log.info("VCenterSoapClient: REST created field '${fieldName}' id=${id}")
                return id
            } finally {
                conn.disconnect()
            }
        } finally {
            deleteRestSession(token)
        }
    }

    /**
     * Deletes a custom attribute definition via the vSphere REST API.
     */
    private void removeFieldDefinitionRest(String fieldName, int key) {
        String token = createRestSession()
        try {
            URL url = new URL("https://${vcHost}/api/vcenter/custom-attributes/${key}")
            HttpsURLConnection conn = openConn(url)
            conn.setConnectTimeout(15000)
            conn.setReadTimeout(30000)
            try {
                conn.setRequestMethod('DELETE')
                conn.setRequestProperty('vmware-api-session-id', token)
                int status = conn.responseCode
                if (status >= 400) {
                    InputStream es = conn.errorStream
                    String response = es?.getText(StandardCharsets.UTF_8.name()) ?: ''
                    log.warn("VCenterSoapClient: REST removeFieldDefinition failed (HTTP ${status})")
                    throw new RuntimeException("Cannot delete attribute '${fieldName}' (HTTP ${status})")
                }
                log.info("VCenterSoapClient: REST deleted field '${fieldName}' (key=${key})")
            } finally {
                conn.disconnect()
            }
        } finally {
            deleteRestSession(token)
        }
    }

    private static String escapeJson(String s) {
        s?.replace('\\', '\\\\')?.replace('"', '\\"') ?: ''
    }

    // -----------------------------------------------------------------------
    // HTTP / SOAP plumbing
    // -----------------------------------------------------------------------

    /**
     * Execute a SOAP call, capture the response and (on Login) the Set-Cookie header.
     */
    private String soapCall(String soapEnvelope, String cookie) {
        log.debug("VCenterSoapClient: soapCall — url='${sdkUrl}', hasCookie=${cookie != null}, payloadBytes=${soapEnvelope?.length()}")
        URL url = new URL(sdkUrl)
        HttpsURLConnection conn = openConn(url)
        conn.setConnectTimeout(15000)  // 15 s connect timeout
        conn.setReadTimeout(60000)     // 60 s read timeout
        try {
            conn.setRequestMethod('POST')
            conn.setDoOutput(true)
            conn.setRequestProperty('Content-Type', 'text/xml; charset=UTF-8')
            conn.setRequestProperty('SOAPAction', '""')
            if (cookie) {
                conn.setRequestProperty('Cookie', cookie)
            }

            byte[] payload = soapEnvelope.getBytes(StandardCharsets.UTF_8)
            conn.setRequestProperty('Content-Length', payload.length.toString())
            conn.outputStream.write(payload)
            conn.outputStream.flush()

            int status = conn.responseCode
            log.debug("VCenterSoapClient: SOAP response — status=${status}")
            InputStream is = status < 400 ? conn.inputStream : conn.errorStream
            String response = is.getText(StandardCharsets.UTF_8.name())

            // Capture session cookie on Login response
            if (!cookie) {
                String setCookie = conn.getHeaderField('Set-Cookie')
                if (setCookie) {
                    sessionCookie = setCookie.split(';')[0].trim()
                    log.debug("VCenterSoapClient: session cookie captured")
                } else {
                    log.debug("VCenterSoapClient: No Set-Cookie header in response")
                }
            }

            if (status >= 400) {
                String faultMsg = parseSoapFaultMessage(response)
                log.warn("VCenterSoapClient: SOAP call failed status=${status} fault='${faultMsg ?: 'not parsed'}'")
                throw new RuntimeException(faultMsg ?: "vCenter SOAP error ${status}")
            }
            return response
        } catch (java.net.NoRouteToHostException e) {
            log.error("VCenterSoapClient: cannot reach vCenter host '${vcHost}' (NoRouteToHost). Check that port 443 is reachable from the Morpheus appliance and no firewall blocks it.")
            throw e
        } catch (java.net.ConnectException e) {
            log.error("VCenterSoapClient: connection refused by vCenter host '${vcHost}'")
            throw e
        } catch (java.net.UnknownHostException e) {
            log.error("VCenterSoapClient: cannot resolve vCenter host name '${vcHost}'. Check DNS from the Morpheus appliance.")
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** DOCTYPE declarations (and therefore XXE / entity expansion) are not accepted in vCenter responses. */
    private static GPathResult parseXml(String xml) {
        new XmlSlurper(false, false, false).parseText(xml)
    }

    // -----------------------------------------------------------------------
    // SOAP envelope builders
    // -----------------------------------------------------------------------

    private static String envelope(String body) {
        """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:vim25="urn:vim25">
  <soapenv:Header/>
  <soapenv:Body>${body}</soapenv:Body>
</soapenv:Envelope>"""
    }

    /**
     * Builds a pyVmomi-compatible SOAP envelope.
     * Method elements carry xmlns="urn:vim25" as a default namespace;
     * child elements are unqualified (no vim25: prefix).
     * This style causes vCenter to establish the SOAP session at the
     * server's negotiated API version rather than vim.version.version3,
     * which enables methods added after vSphere 2.5 such as
     * AddFieldDefinition and RemoveFieldDefinition.
     */
    private static String pvEnvelope(String bodyContent) {
        """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<soapenv:Body>
${bodyContent}
</soapenv:Body>
</soapenv:Envelope>"""
    }

    private static String buildRetrieveServiceContentEnvelope() {
        pvEnvelope("""<RetrieveServiceContent xmlns="urn:vim25">
<_this type="ServiceInstance">ServiceInstance</_this>
</RetrieveServiceContent>""")
    }

    private static String buildLoginEnvelope(String user, String pass, String sessionMgr) {
        pvEnvelope("""<Login xmlns="urn:vim25">
<_this type="SessionManager">${xmlEscape(sessionMgr)}</_this>
<userName>${xmlEscape(user)}</userName>
<password>${xmlEscape(pass)}</password>
</Login>""")
    }

    private static String buildLogoutEnvelope(String sessionMgr) {
        pvEnvelope("""<Logout xmlns="urn:vim25">
<_this type="SessionManager">${xmlEscape(sessionMgr)}</_this>
</Logout>""")
    }

    private static String buildRetrieveFieldDefinitionsEnvelope(String cfmRef, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>CustomFieldsManager</type>
    <all>false</all>
    <pathSet>field</pathSet>
  </propSet>
  <objectSet>
    <obj type="CustomFieldsManager">${xmlEscape(cfmRef)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildRetrieveVmCustomValuesEnvelope(String vmMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>VirtualMachine</type>
    <all>false</all>
    <pathSet>customValue</pathSet>
  </propSet>
  <objectSet>
    <obj type="VirtualMachine">${xmlEscape(vmMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    private static String buildSetFieldEnvelope(String cfmRef, String vmMor, int key, String value) {
        pvEnvelope("""<SetField xmlns="urn:vim25">
<_this type="CustomFieldsManager">${xmlEscape(cfmRef)}</_this>
<entity type="VirtualMachine">${xmlEscape(vmMor)}</entity>
<key>${key}</key>
<value>${xmlEscape(value)}</value>
</SetField>""")
    }

    /**
     * RetrieveProperties for one VM's name, used only to confirm the VM exists.
     */
    private static String buildVmExistsEnvelope(String vmMor, String pcRef) {
        pvEnvelope("""<RetrieveProperties xmlns="urn:vim25">
<_this type="PropertyCollector">${xmlEscape(pcRef)}</_this>
<specSet>
  <propSet>
    <type>VirtualMachine</type>
    <all>false</all>
    <pathSet>name</pathSet>
  </propSet>
  <objectSet>
    <obj type="VirtualMachine">${xmlEscape(vmMor)}</obj>
    <skip>false</skip>
  </objectSet>
</specSet>
</RetrieveProperties>""")
    }

    // NOTE: vCenter 8.x uses 'AddCustomFieldDef' (not 'AddFieldDefinition') at API 5.0+.
    // The versionId attribute on each element tells vCenter to dispatch in the API version
    // context returned by RetrieveServiceContent, enabling the newer method names.
    // This is exactly what pyVmomi sends on the wire.
    private String buildAddFieldDefinitionEnvelope(String cfmRef, String fieldName, String fieldType) {
        // A Global definition has no moType; any other type is validated by the caller.
        String moType = (fieldType && fieldType != 'Global')
            ? """<moType versionId="${apiVersion}">${xmlEscape(fieldType)}</moType>""" : ''
        """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
<soapenv:Body><AddCustomFieldDef xmlns="urn:vim25"><_this versionId="${apiVersion}" type="CustomFieldsManager">${xmlEscape(cfmRef)}</_this><name versionId="${apiVersion}">${xmlEscape(fieldName)}</name>${moType}</AddCustomFieldDef></soapenv:Body>
</soapenv:Envelope>"""
    }

    private String buildRemoveFieldDefinitionEnvelope(String cfmRef, int key) {
        """<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
<soapenv:Body><RemoveCustomFieldDef xmlns="urn:vim25"><_this versionId="${apiVersion}" type="CustomFieldsManager">${xmlEscape(cfmRef)}</_this><key versionId="${apiVersion}">${key}</key></RemoveCustomFieldDef></soapenv:Body>
</soapenv:Envelope>"""
    }

    private static String xmlEscape(String s) {
        s?.replace('&', '&amp;')
          ?.replace('<', '&lt;')
          ?.replace('>', '&gt;')
          ?.replace('"', '&quot;')
          ?.replace("'", '&apos;') ?: ''
    }
}
