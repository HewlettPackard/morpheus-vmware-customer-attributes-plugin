package com.morpheus.customAttributes

import javax.naming.ldap.LdapName
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Per-connection TLS settings. Every connection gets its own socket factory and host name check, so
 * the result does not depend on JVM-wide defaults that another plugin may have replaced. By default the
 * certificate chain is checked against the JVM trust store and the host name against the certificate.
 * Trusting an unverified certificate is only done for the one connection it is requested for, when an
 * administrator has switched the plugin setting on.
 */
final class TlsSupport {

    private static volatile SSLSocketFactory trustAllFactory
    private static volatile SSLSocketFactory verifyingFactory

    private static final HostnameVerifier STRICT_VERIFIER = { String host, SSLSession session -> hostMatches(host, session) } as HostnameVerifier

    private TlsSupport() {}

    static void apply(HttpsURLConnection conn, boolean allowUntrusted) {
        if (allowUntrusted) {
            conn.setSSLSocketFactory(trustAllFactory())
            conn.setHostnameVerifier({ String host, SSLSession session -> true } as HostnameVerifier)
        } else {
            conn.setSSLSocketFactory(verifyingFactory())
            conn.setHostnameVerifier(STRICT_VERIFIER)
        }
    }

    private static SSLSocketFactory verifyingFactory() {
        SSLSocketFactory f = verifyingFactory
        if (f == null) {
            synchronized (TlsSupport) {
                if (verifyingFactory == null) {
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    tmf.init((KeyStore) null)
                    SSLContext sc = SSLContext.getInstance('TLS')
                    sc.init(null, tmf.getTrustManagers(), new SecureRandom())
                    verifyingFactory = sc.getSocketFactory()
                }
                f = verifyingFactory
            }
        }
        return f
    }

    /** Matches the host against the certificate's subject alternative names, or its CN when it has none. */
    static boolean hostMatches(String host, SSLSession session) {
        try {
            def certs = session.getPeerCertificates()
            if (!certs) return false
            X509Certificate cert = (X509Certificate) certs[0]
            String h = stripBrackets(host?.toLowerCase())
            if (!h) return false
            boolean isIp = h.contains(':') || (h ==~ /^[0-9.]+$/)
            Collection<List<?>> sans = cert.getSubjectAlternativeNames()
            if (sans != null && !sans.isEmpty()) {
                for (List<?> san : sans) {
                    int type = ((Number) san.get(0)).intValue()
                    String value = san.get(1)?.toString()?.toLowerCase()
                    if (value == null) continue
                    if (isIp && type == 7 && stripBrackets(value) == h) return true
                    if (!isIp && type == 2 && dnsMatches(h, value)) return true
                }
                return false
            }
            if (isIp) return false
            String cn = commonName(cert)
            return cn != null && dnsMatches(h, cn.toLowerCase())
        } catch (Exception ignored) {
            return false
        }
    }

    private static String stripBrackets(String s) {
        return (s != null && s.startsWith('[') && s.endsWith(']')) ? s.substring(1, s.length() - 1) : s
    }

    private static boolean dnsMatches(String host, String pattern) {
        if (pattern.startsWith('*.')) {
            String rest = pattern.substring(1)
            int dot = host.indexOf('.')
            return dot > 0 && pattern.substring(2).contains('.') && host.substring(dot) == rest
        }
        return host == pattern
    }

    private static String commonName(X509Certificate cert) {
        LdapName dn = new LdapName(cert.getSubjectX500Principal().getName())
        for (int i = dn.size() - 1; i >= 0; i--) {
            def rdn = dn.getRdn(i)
            if ('CN'.equalsIgnoreCase(rdn.getType())) return rdn.getValue()?.toString()
        }
        return null
    }

    private static SSLSocketFactory trustAllFactory() {
        SSLSocketFactory f = trustAllFactory
        if (f == null) {
            synchronized (TlsSupport) {
                if (trustAllFactory == null) {
                    TrustManager[] trustAll = [new X509TrustManager() {
                        X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0] }
                        void checkClientTrusted(X509Certificate[] c, String a) {}
                        void checkServerTrusted(X509Certificate[] c, String a) {}
                    }] as TrustManager[]
                    SSLContext sc = SSLContext.getInstance('TLS')
                    sc.init(null, trustAll, new SecureRandom())
                    trustAllFactory = sc.getSocketFactory()
                }
                f = trustAllFactory
            }
        }
        return f
    }

    // ----- Untrusted-certificate detection (used only when allowUntrusted is on) -----

    /** Records whether a certificate presented on an allow-untrusted connection failed normal verification. */
    static class TrustOutcome {
        volatile boolean untrusted = false
    }

    /**
     * Socket factory that accepts any certificate (so a self-signed vCenter still connects) but records,
     * via the supplied outcome, whether the chain would have failed the JVM trust store. Build one per
     * client and reuse it; the shared outcome lets the caller warn that the server was not verified.
     */
    static SSLSocketFactory recordingTrustAllFactory(TrustOutcome outcome) {
        X509TrustManager defaultTm = defaultTrustManager()
        TrustManager[] tms = [new X509TrustManager() {
            X509Certificate[] getAcceptedIssuers() { return defaultTm != null ? defaultTm.getAcceptedIssuers() : new X509Certificate[0] }
            void checkClientTrusted(X509Certificate[] c, String a) {}
            void checkServerTrusted(X509Certificate[] c, String a) {
                if (defaultTm == null) { outcome.untrusted = true; return }
                try {
                    defaultTm.checkServerTrusted(c, a)
                } catch (Exception ignored) {
                    outcome.untrusted = true
                }
            }
        }] as TrustManager[]
        SSLContext sc = SSLContext.getInstance('TLS')
        sc.init(null, tms, new SecureRandom())
        return sc.getSocketFactory()
    }

    /** Host name verifier that always allows the connection but records a host-name mismatch as untrusted. */
    static HostnameVerifier recordingVerifier(TrustOutcome outcome) {
        return { String host, SSLSession session ->
            if (!hostMatches(host, session)) outcome.untrusted = true
            return true
        } as HostnameVerifier
    }

    private static X509TrustManager defaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init((KeyStore) null)
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) return (X509TrustManager) tm
            }
        } catch (Exception ignored) {}
        return null
    }
}
