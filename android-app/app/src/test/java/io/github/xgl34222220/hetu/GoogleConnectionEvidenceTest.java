package io.github.xgl34222220.hetu;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleConnectionEvidenceTest {
    @Test public void authenticationAndPlayDomainsAreIncluded() {
        for (String host : new String[]{"accounts.google.com", "android.clients.google.com", "play.googleapis.com", "oauth2.googleapis.com"})
            assertTrue(host, GoogleConnectionEvidence.matches("", host, -1, Collections.emptySet()));
    }
    @Test public void googleMetadataIsCaseInsensitiveAndAcceptsDnsRootDot() {
        assertTrue(GoogleConnectionEvidence.matches("", "Accounts.Google.Com.", -1, null));
    }
    @Test public void suffixLookalikesDoNotConsumeReservedGoogleQuota() {
        for (String host : new String[]{"notgoogle.com", "google.com.evil.example", "mygoogleapis.com", "accounts.google.com@example.org", "https://accounts.google.com/"})
            assertFalse(host, GoogleConnectionEvidence.matches("chrome", host, -1, null));
    }
    @Test public void googleSystemProcessesMatchEvenWhenHostIsMissing() {
        for (String process : new String[]{"com.google.android.gms", "com.google.android.gms:persistent", "com.google.android.gsf", "com.android.vending"})
            assertTrue(process, GoogleConnectionEvidence.matches(process, "", -1, null));
    }
    @Test public void processLookalikesDoNotMatch() {
        assertFalse(GoogleConnectionEvidence.matches("com.google.android.gms.fake", "", -1, null));
        assertFalse(GoogleConnectionEvidence.matches("com.android.vending.evil", "", -1, null));
    }
    @Test public void resolvedUidCanMatchIpOnlyConnection() {
        assertTrue(GoogleConnectionEvidence.matches("", "", 10123, Collections.singleton(10123)));
        assertFalse(GoogleConnectionEvidence.matches("", "", 10124, Collections.singleton(10123)));
    }
    @Test public void unknownUidNeverProvesGoogleOwnership() {
        assertFalse(GoogleConnectionEvidence.matches("", "", -1, Collections.singleton(-1)));
    }
    @Test public void transportEvidenceExplicitlyDoesNotClaimAuthentication() {
        assertTrue(GoogleConnectionEvidence.LIMITATION.contains("不能证明帐号认证成功"));
        assertTrue(GoogleConnectionEvidence.LIMITATION.contains("未读取帐号、Cookie 或认证令牌"));
    }
}
