package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NativeAdapterPathsTest {
    @Test
    fun `paths use no-node directory contract`() {
        val root = File("root")
        val paths = NativeAdapterPaths(root)

        assertEquals(File(root, "web"), paths.webDir)
        assertEquals(File(File(root, "state"), "installed-web-manifest.json"), paths.webManifestFile)
        assertEquals(File(root, "user_config"), paths.userConfigDir)
        assertEquals(File(File(root, "user_config"), "settings.json"), paths.settingsFile)
        assertEquals(
            File(File(root, "user_config"), "provider-openai-compatible.json"),
            paths.providerConfigFile
        )
        assertEquals(File(root, "user_data"), paths.userDataDir)
        assertEquals(File(File(root, "user_data"), "characters"), paths.charactersDir)
        assertEquals(File(File(root, "user_data"), "chats"), paths.chatsDir)
        assertEquals(File(File(root, "user_data"), "world_info"), paths.worldInfoDir)
        assertEquals(File(File(root, "user_data"), "backgrounds"), paths.backgroundsDir)
        assertEquals(File(File(root, "user_data"), "uploads"), paths.uploadsDir)
        assertEquals(File(File(root, "user_data"), "user_images"), paths.userImagesDir)
        assertEquals(File(File(root, "user_data"), "extensions"), paths.extensionsDir)
        assertEquals(File(File(root, "user_data"), "image-metadata.json"), paths.imageMetadataFile)
        assertEquals(File(root, "secrets"), paths.secretsDir)
        assertEquals(File(root, "logs"), paths.logsDir)
        assertEquals(File(root, "state"), paths.stateDir)
        assertEquals(File(File(root, "state"), "native-adapter-state.json"), paths.adapterStateFile)
        assertEquals(File(File(root, "state"), "extensions.json"), paths.extensionRegistryFile)
        assertEquals(File(root, "SillyTavern"), paths.legacySillyTavernDir)
    }

    @Test
    fun `state model exposes all statuses and defaults`() {
        assertEquals(
            listOf(
                NativeAdapterStatus.STARTING,
                NativeAdapterStatus.RUNNING,
                NativeAdapterStatus.FAILED,
                NativeAdapterStatus.STOPPED,
                NativeAdapterStatus.MIGRATING,
                NativeAdapterStatus.MIGRATION_FAILED
            ),
            NativeAdapterStatus.values().toList()
        )

        val state = NativeAdapterState(status = NativeAdapterStatus.STARTING)

        assertEquals(NativeAdapterStatus.STARTING, state.status)
        assertEquals(null, state.port)
        assertEquals("", state.message)
    }
}
