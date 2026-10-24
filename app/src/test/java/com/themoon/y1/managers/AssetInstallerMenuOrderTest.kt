package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetInstallerMenuOrderTest {

    @Test
    fun insertsNewItemAfterAnchorAndKeepsTrailingComma() {
        val order = "btn_now,btn_music,btn_videos,btn_set,"

        val result = AssetInstaller.insertMenuItemAfter(order, "btn_shuffle", "btn_music")

        assertEquals("btn_now,btn_music,btn_shuffle,btn_videos,btn_set,", result)
    }

    @Test
    fun keepsOrderWhenItemIsAlreadyThere() {
        val order = "btn_shuffle,btn_now,btn_music,"

        assertEquals(order, AssetInstaller.insertMenuItemAfter(order, "btn_shuffle", "btn_music"))
    }

    @Test
    fun keepsOrderWhenAnchorIsMissing() {
        val order = "custom_a,custom_b,"

        assertEquals(order, AssetInstaller.insertMenuItemAfter(order, "btn_shuffle", "btn_music"))
    }
}
