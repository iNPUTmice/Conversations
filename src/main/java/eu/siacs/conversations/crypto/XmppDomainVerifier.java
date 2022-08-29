package eu.siacs.conversations.crypto;

import android.util.Log;
import android.util.Pair;

import com.google.common.collect.ImmutableList;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

import java.io.IOException;
import java.net.IDN;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

public class XmppDomainVerifier {

    private static final String LOGTAG = "XmppDomainVerifier";

    private static final String SRV_NAME = "1.3.6.1.5.5.7.8.7";
    private static final String XMPP_ADDR = "1.3.6.1.5.5.7.8.5";

    private static List<String> getCommonNames(X509Certificate certificate) {
        List<String> domains = new ArrayList<>();
        try {
            X500Name x500name = new JcaX509CertificateHolder(certificate).getSubject();
            RDN[] rdns = x500name.getRDNs(BCStyle.CN);
            for (int i = 0; i < rdns.length; ++i) {
                domains.add(IETFUtils.valueToString(x500name.getRDNs(BCStyle.CN)[i].getFirst().getValue()));
            }
            return domains;
        } catch (CertificateEncodingException e) {
            return domains;
        }
    }

    private static Pair<String, String> parseOtherName(byte[] otherName) {
        try {
            ASN1Primitive asn1Primitive = ASN1Primitive.fromByteArray(otherName);
            if (asn1Primitive instanceof DERTaggedObject) {
                ASN1Primitive inner = ((DERTaggedObject) asn1Primitive).getObject();
                if (inner instanceof DLSequence) {
                    DLSequence sequence = (DLSequence) inner;
                    if (sequence.size() >= 2 && sequence.getObjectAt(1) instanceof DERTaggedObject) {
                        String oid = sequence.getObjectAt(0).toString();
                        ASN1Primitive value = ((DERTaggedObject) sequence.getObjectAt(1)).getObject();
                        if (value instanceof DERUTF8String) {
                            return new Pair<>(oid, ((DERUTF8String) value).getString());
                        } else if (value instanceof DERIA5String) {
                            return new Pair<>(oid, ((DERIA5String) value).getString());
                        }
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean matchDomain(final String needle, final List<String> haystack) {
        for (final String entry : haystack) {
            if (entry.startsWith("*.")) {
                int offset = 0;
                while (offset < needle.length()) {
                    int i = needle.indexOf('.', offset);
                    if (i < 0) {
                        break;
                    }
                    if (needle.substring(i).equalsIgnoreCase(entry.substring(1))) {
                        return true;
                    }
                    offset = i + 1;
                }
            } else {
                if (entry.equalsIgnoreCase(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean verify(final String unicodeDomain, final String unicodeHostname, SSLSession sslSession) throws SSLPeerUnverifiedException {
        final String domain = IDN.toASCII(unicodeDomain);
        final String hostname = unicodeHostname == null ? null : IDN.toASCII(unicodeHostname);
        final Certificate[] chain = sslSession.getPeerCertificates();
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            return false;
        }
        final X509Certificate certificate = (X509Certificate) chain[0];
        final List<String> commonNames = getCommonNames(certificate);
        if (isSelfSigned(certificate)) {
            if (commonNames.size() == 1 && matchDomain(domain, commonNames)) {
                Log.d(LOGTAG, "accepted CN in self signed cert as work around for " + domain);
                return true;
            }
        }
        try {
            final ValidDomains validDomains = parseValidDomains(certificate);
            Log.d(LOGTAG, "searching for " + domain + " in srvNames: " + validDomains.srvNames + " xmppAddrs: " + validDomains.xmppAddrs + " domains:" + validDomains.domains);
            if (hostname != null) {
                Log.d(LOGTAG, "also trying to verify hostname " + hostname);
            }
            return validDomains.xmppAddrs.contains(domain)
                    || validDomains.srvNames.contains("_xmpp-client." + domain)
                    || matchDomain(domain, validDomains.domains)
                    || (hostname != null && matchDomain(hostname, validDomains.domains));
        } catch (final Exception e) {
            return false;
        }
    }

    public static ValidDomains parseValidDomains(final X509Certificate certificate) throws CertificateParsingException {
        final List<String> commonNames = getCommonNames(certificate);
        final Collection<List<?>> alternativeNames = certificate.getSubjectAlternativeNames();
        final List<String> xmppAddrs = new ArrayList<>();
        final List<String> srvNames = new ArrayList<>();
        final List<String> domains = new ArrayList<>();
        if (alternativeNames != null) {
            for (List<?> san : alternativeNames) {
                final Integer type = (Integer) san.get(0);
                if (type == 0) {
                    final Pair<String, String> otherName = parseOtherName((byte[]) san.get(1));
                    if (otherName != null && otherName.first != null && otherName.second != null) {
                        switch (otherName.first) {
                            case SRV_NAME:
                                srvNames.add(otherName.second.toLowerCase(Locale.US));
                                break;
                            case XMPP_ADDR:
                                xmppAddrs.add(otherName.second.toLowerCase(Locale.US));
                                break;
                            default:
                                Log.d(LOGTAG, "oid: " + otherName.first + " value: " + otherName.second);
                        }
                    }
                } else if (type == 2) {
                    final Object value = san.get(1);
                    if (value instanceof String) {
                        domains.add(((String) value).toLowerCase(Locale.US));
                    }
                }
            }
        }
        if (srvNames.size() == 0 && xmppAddrs.size() == 0 && domains.size() == 0) {
            domains.addAll(commonNames);
        }
        return new ValidDomains(xmppAddrs, srvNames, domains);
    }

    public static final class ValidDomains {
        final List<String> xmppAddrs;
        final List<String> srvNames;
        final List<String> domains;

        private ValidDomains(List<String> xmppAddrs, List<String> srvNames, List<String> domains) {
            this.xmppAddrs = xmppAddrs;
            this.srvNames = srvNames;
            this.domains = domains;
        }

        public List<String> all() {
            ImmutableList.Builder<String> all = new ImmutableList.Builder<>();
            all.addAll(xmppAddrs);
            all.addAll(srvNames);
            all.addAll(domains);
            return all.build();
        }
    }

    private boolean isSelfSigned(X509Certificate certificate) {
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
