package com.highsockscapital.sunshine.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AlpineMirrorTest {
    @Test
    fun keepsOfficialRepositoriesAsFallbackForChinaMirror() {
        val repositories = """
            https://dl-cdn.alpinelinux.org/alpine/v3.23/main
            https://dl-cdn.alpinelinux.org/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            chinaApkRepositories(repositories),
        )
    }

    @Test
    fun repairsLegacyMirrorOnlyRepositories() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            chinaApkRepositories(repositories),
        )
    }

    @Test
    fun usesOnlyOfficialRepositoriesForInternationalNetwork() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://dl-cdn.alpinelinux.org/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
            https://dl-cdn.alpinelinux.org/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
            """.trimIndent(),
            apkRepositories(repositories, ApkNetworkEnvironment.International),
        )
    }

    @Test
    fun usesBothSourcesWhenIpDetectionIsUnavailable() {
        val repositories = """
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
            https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
        """.trimIndent()

        assertEquals(
            """
                https://dl-cdn.alpinelinux.org/alpine/v3.23/main
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/main
                https://dl-cdn.alpinelinux.org/alpine/v3.23/community
                https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.23/community
            """.trimIndent(),
            apkRepositories(repositories, ApkNetworkEnvironment.Unknown),
        )
    }

    @Test
    fun parsesCountryCodeFromCloudflareTrace() {
        assertEquals("CN", parseCloudflareCountryCode("ip=203.0.113.1\nloc=cn\ntls=TLSv1.3\n"))
        assertEquals(null, parseCloudflareCountryCode("ip=203.0.113.1\n"))
    }
}
