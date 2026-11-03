package com.morpheus.customAttributes

import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.User
import groovy.json.JsonOutput

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Request guard, authorization, input validation and output encoding helpers
 * used by the plugin's controllers and tab providers.
 */
final class SecurityUtil {

    static final int MAX_ATTR_NAME = 128
    static final int MAX_ATTR_VALUE = 1024
    static final int MAX_NAME = 255
    static final int MAX_PATH = 1024

    static final String REQUIRED_HEADER = 'X-Requested-With'
    static final String REQUIRED_HEADER_VALUE = 'XMLHttpRequest'

    private static final Pattern ISO_PATH = Pattern.compile('^\\[([^\\[\\]\\r\\n]{1,255})\\] ?([^\\r\\n]{1,1024}\\.[iI][sS][oO])$')
    private static final Pattern HOST = Pattern.compile('^[A-Za-z0-9._\\-:\\[\\]]{1,255}$')
    private static final Pattern VM_ID = Pattern.compile('^vm-[0-9]{1,18}$')

    private SecurityUtil() {}

    // -----------------------------------------------------------------------
    // Request guard
    // -----------------------------------------------------------------------

    /**
     * Returns null when the request may proceed, otherwise a short public reason.
     * Requires GET or POST, the custom header (a cross-site page cannot add it without a CORS
     * pre-flight) and, when the browser sends one, an Origin/Referer that matches this host.
     */
    static String checkRequest(Object request) {
        if (request == null) return 'Invalid request'
        String method = String.valueOf(request.getMethod())
        if (method != 'POST' && method != 'GET') return 'Method not allowed'
        if (request.getHeader(REQUIRED_HEADER) != REQUIRED_HEADER_VALUE) return 'Missing required request header'
        if (!isSameOrigin(request)) return 'Cross-origin request rejected'
        return null
    }

    static boolean isSameOrigin(Object request) {
        String origin = request.getHeader('Origin') ?: request.getHeader('Referer')
        if (!origin) return true
        String originHost
        try {
            originHost = new URI(origin).getHost()?.toLowerCase()
        } catch (Exception ignored) {
            return false
        }
        if (!originHost) return false
        Set<String> allowed = new HashSet<String>()
        [request.getHeader('Host'), request.getHeader('X-Forwarded-Host'), request.getServerName()].each { Object h ->
            if (h) {
                h.toString().split(',').each { String part -> allowed.add(stripPort(part.trim().toLowerCase())) }
            }
        }
        return allowed.contains(originHost)
    }

    private static String stripPort(String hostPort) {
        if (hostPort.startsWith('[')) {
            int end = hostPort.indexOf(']')
            return end > 0 ? hostPort.substring(0, end + 1) : hostPort
        }
        int colon = hostPort.lastIndexOf(':')
        return colon > 0 ? hostPort.substring(0, colon) : hostPort
    }

    // -----------------------------------------------------------------------
    // Authorization
    // -----------------------------------------------------------------------

    static String permissionLevel(User user, String code) {
        return user?.permissions?.get(code)?.toString()?.toLowerCase() ?: 'none'
    }

    static boolean hasFull(User user, String code) {
        return permissionLevel(user, code) == 'full'
    }

    static boolean hasRead(User user, String code) {
        String level = permissionLevel(user, code)
        return level == 'read' || level == 'full'
    }

    /** True when the user belongs to the target's account, or to the master account. */
    static boolean canAccessAccount(User user, Account target) {
        Long userAccountId = user?.account?.id
        Long targetAccountId = target?.id
        if (userAccountId == null || targetAccountId == null) return false
        if (userAccountId == targetAccountId) return true
        return user.account.masterAccount == true
    }

    static boolean isVmwareCloud(Cloud cloud) {
        String code = cloud?.cloudType?.code?.toLowerCase() ?: ''
        String name = cloud?.cloudType?.name?.toLowerCase() ?: ''
        return [code, name].any { String s -> s.contains('vmware') || s.contains('vsphere') || s.contains('esxi') }
    }

    // -----------------------------------------------------------------------
    // Input validation (each returns null when the input is not acceptable)
    // -----------------------------------------------------------------------

    static Long parseId(String s) {
        if (!s || !(s ==~ /^[0-9]{1,18}$/)) return null
        return Long.valueOf(s)
    }

    static String cleanText(String s, int max, boolean allowEmpty) {
        if (s == null) return allowEmpty ? '' : null
        String t = s.trim()
        if (t.isEmpty()) return allowEmpty ? '' : null
        if (t.length() > max) return null
        if (hasControlChars(t)) return null
        return t
    }

    static String cleanAttrName(String s) { return cleanText(s, MAX_ATTR_NAME, false) }

    static String cleanAttrValue(String s) { return cleanText(s, MAX_ATTR_VALUE, true) }

    /** Scope of a new attribute: only Global or VirtualMachine; a missing value means VirtualMachine, anything else is rejected. */
    static String cleanAttrType(String s) {
        String t = s?.trim()
        if (!t) return 'VirtualMachine'
        return (t == 'Global' || t == 'VirtualMachine') ? t : null
    }

    static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) return true
        }
        return false
    }

    static boolean isValidHost(String host) {
        return host != null && HOST.matcher(host).matches()
    }

    static boolean isValidVmId(String id) {
        return id != null && VM_ID.matcher(id).matches()
    }

    static String cleanDatastoreName(String name) {
        String t = name?.trim()
        if (!t || t.length() > MAX_NAME) return null
        if (t.contains('[') || t.contains(']') || t.contains('/') || t.contains('\\')) return null
        if (hasControlChars(t)) return null
        return t
    }

    /** Folder path inside a datastore, '/' for the root. Rejects '.', '..', empty segments, backslashes and control characters. */
    static String cleanRelativePath(String path) {
        String p = (path ?: '/').trim()
        if (p.length() > MAX_PATH) return null
        if (p == '/' || p.isEmpty()) return '/'
        if (p.contains('\\') || hasControlChars(p)) return null
        String body = p.startsWith('/') ? p.substring(1) : p
        for (String seg : body.split('/', -1)) {
            if (seg.isEmpty() || seg == '.' || seg == '..') return null
        }
        return '/' + body
    }

    /** Splits "[datastore] folder/file.iso" into [datastore, relativePath]; null when malformed or unsafe. */
    static List<String> parseIsoPath(String iso) {
        if (!iso) return null
        Matcher m = ISO_PATH.matcher(iso.trim())
        if (!m.matches()) return null
        String datastore = cleanDatastoreName(m.group(1))
        String relative = m.group(2)
        if (!datastore || cleanRelativePath('/' + relative) == null) return null
        return [datastore, relative]
    }

    // -----------------------------------------------------------------------
    // Output encoding
    // -----------------------------------------------------------------------

    /** JSON that is safe inside an HTML script element: no raw '<', '>', '&' or line separators. */
    static String jsonForScript(Object value) {
        String json = JsonOutput.toJson(value)
        String bs = '\\'
        return json.replace('<', bs + 'u003c')
                   .replace('>', bs + 'u003e')
                   .replace('&', bs + 'u0026')
                   .replace(String.valueOf((char) 0x2028), bs + 'u2028')
                   .replace(String.valueOf((char) 0x2029), bs + 'u2029')
    }

    static String newRef() {
        return UUID.randomUUID().toString().substring(0, 8)
    }
}
