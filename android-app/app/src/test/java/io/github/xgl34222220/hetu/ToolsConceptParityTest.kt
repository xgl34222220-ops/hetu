package io.github.xgl34222220.hetu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation shared by full-page configuration forms and file download dialogs. */
class ToolsConceptParityTest {
    @Test fun configurationLinksRequireAnActualHttpHost() {
        listOf("https://example.com/config.yaml", "http://127.0.0.1:3000/sub?token=abc", "https://user:pass@example.com/config.yaml")
            .forEach { assertTrue(it, hxConfigHttpUrl(it)) }
    }

    @Test fun bareHostsAndSchemePrefixesRemainInlineValidationErrors() {
        listOf("example.com/config.yaml", "httpwhatever", "https://", "https:///config.yaml", "ftp://example.com/config.yaml", "https://exa mple.com/config.yaml")
            .forEach { assertFalse(it, hxConfigHttpUrl(it)) }
    }

    @Test fun linksCannotContainLineBreaksOrExceedTheRepositoryLimit() {
        assertFalse(hxConfigHttpUrl("https://example.com/\nconfig.yaml"))
        assertFalse(hxConfigHttpUrl("https://example.com/" + "a".repeat(4096)))
        assertTrue(hxConfigHttpUrl("  https://example.com/config.yaml  "))
    }
}
