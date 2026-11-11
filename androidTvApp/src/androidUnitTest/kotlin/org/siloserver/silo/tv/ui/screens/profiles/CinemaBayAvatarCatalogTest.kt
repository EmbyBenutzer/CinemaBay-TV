package org.siloserver.silo.tv.ui.screens.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CinemaBayAvatarCatalogTest {
    @Test fun existingProfileAvatarIsNotReplacedWithoutASelection() {
        val old = "preset:dicebear:fun-emoji:cosmic-otter"
        assertEquals(old, TvProfileAvatarPresets.effectiveAvatarRef("netflix", "cosmic-otter", 0, "David", old))
        assertEquals(old, TvProfileAvatarPresets.parseRef(old)?.ref)
    }
    @Test fun parsesNamedProviderAndCollection() {
        val json = """[{"provider":"prime","collection":"The Boys","name":"Starlight","url":"https://example.org/avatar.png","id":"abc"}]"""
        val portrait = TvProfileAvatarPresets.parseCatalog(json).single()
        assertEquals("prime", portrait.provider)
        assertEquals("The Boys", portrait.collection)
        assertEquals("Starlight", portrait.name)
    }
    @Test fun rejectsNonHttpsImageAddresses() {
        assertFailsWith<IllegalArgumentException> {
            TvProfileAvatarPresets.parseCatalog("""[{"provider":"prime","collection":"The Boys","name":"Starlight","url":"http://example.org/avatar.png","id":"abc"}]""")
        }
    }
}
