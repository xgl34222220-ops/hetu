package io.github.xgl34222220.hetu
import org.junit.Assert.*
import org.junit.Test
class CoreManagerConcept57Test {
    private val all = listOf("mihomo", "mihomo-smart", "sing-box", "sing-box-ref1nd", "xray", "v2fly", "hysteria").map {
        ProxyCoreRemoteStatus(it,it,false,false,"","",false,true,false,"test")
    }
    @Test fun defaultSurfaceHasExactlyThreeReferenceCardsInReferenceOrder() {
        assertEquals(listOf("mihomo", "xray", "sing-box"), coreManagerCards(all, emptyMap()).map { it.second.id })
    }
    @Test fun variantSelectionChangesManagementTargetWithoutMutatingSourceCatalog() {
        val before=all.toList()
        val selected=coreManagerCards(all,mapOf("mihomo" to "mihomo-smart", "sing-box" to "sing-box-ref1nd"))
        assertEquals(listOf("mihomo-smart","xray","sing-box-ref1nd"),selected.map { it.second.id })
        assertEquals(before,all)
    }
    @Test fun independentCoresAreAvailableButNeverNamedFamilyVariants() {
        assertFalse(coreFamilyVariantIds("mihomo").contains("hysteria"))
        assertFalse(coreFamilyVariantIds("xray").contains("v2fly"))
        assertTrue(coreManagerVariantIds("xray").containsAll(listOf("v2fly","hysteria")))
        assertEquals("hysteria",coreManagerCards(all,mapOf("mihomo" to "hysteria")).first().second.id)
    }
    @Test fun invalidOrRemovedPreferenceFallsBackToFamilyWithoutNewCore() {
        assertEquals("mihomo",coreManagerCards(all,mapOf("mihomo" to "not-a-core")).first().second.id)
        assertEquals("mihomo",coreManagerCards(all.filter { it.id!="mihomo-smart" },mapOf("mihomo" to "mihomo-smart")).first().second.id)
    }
}
