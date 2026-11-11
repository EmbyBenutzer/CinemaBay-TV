package org.siloserver.silo.tv.update

import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class CinemaBayUpdatesTest {
    @Suppress("DEPRECATION")
    private fun apkFixture(packageId:String?=null,version:Int=29,key:String="01020304",minimum:Int=24):Pair<android.content.Context,java.io.File> {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val shadow=org.robolectric.Shadows.shadowOf(context.packageManager)
        fun info(id:String,code:Int,signer:String,min:Int)=android.content.pm.PackageInfo().apply {
            packageName=id;versionCode=code;signatures=arrayOf(android.content.pm.Signature(signer))
            applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=id;minSdkVersion=min}
        }
        shadow.installPackage(info(context.packageName,27,"01020304",24))
        val file=java.io.File(context.cacheDir,"candidate.part").apply{writeText("PackageManager fixture, not an APK")}
        shadow.setPackageArchiveInfo(file.absolutePath,info(packageId ?: context.packageName,version,key,minimum))
        return context to file
    }
    @Test @Config(sdk=[24]) fun acceptsHigherVersionWithSamePackageAndSignature() {
        val (context,file)=apkFixture();CinemaBayUpdates.validateApk(context,file)
    }
    @Test @Config(sdk=[24]) fun rejectsDifferentPackage() {
        val (context,file)=apkFixture(packageId="other.app");assertFails{CinemaBayUpdates.validateApk(context,file)}
    }
    @Test @Config(sdk=[24]) fun rejectsDowngradeAndEqualVersionCode() {
        for(code in listOf(25,27)){val (context,file)=apkFixture(version=code);assertFails{CinemaBayUpdates.validateApk(context,file)}}
    }
    @Test @Config(sdk=[24]) fun rejectsDifferentSigningKey() {
        val (context,file)=apkFixture(key="05060708");assertFails{CinemaBayUpdates.validateApk(context,file)}
    }
    @Test @Config(sdk=[24]) fun rejectsUnsupportedAndroidRequirement() {
        val (context,file)=apkFixture(minimum=35);assertFails{CinemaBayUpdates.validateApk(context,file)}
    }
    private fun release(tag:String="v0.1.4",url:String="https://github.com/EmbyBenutzer/CinemaBay-TV/releases/download/v0.1.4/CinemaBay-0.1.4.apk",digest:String="sha256:"+"a".repeat(64),draft:Boolean=false,preview:Boolean=false):JSONObject =
        JSONObject().put("tag_name",tag).put("body","Neue Funktionen").put("draft",draft).put("prerelease",preview)
            .put("assets",JSONArray().put(JSONObject().put("name","CinemaBay-${tag.removePrefix("v")}.apk")
                .put("browser_download_url",url).put("state","uploaded").put("size",38_000_000).put("digest",digest)))
    @Test fun versionsAreNumericAndStableOnly() {
        assertTrue(cinemaBayNewerVersion("v0.1.10","0.1.9-cinemabay"))
        assertTrue(cinemaBayNewerVersion("0.1.3","0.1.2-updater-preview"))
        assertFalse(cinemaBayNewerVersion("0.1.2","0.1.3-cinemabay"))
        assertFalse(cinemaBayNewerVersion("0.1.3","0.1.3-cinemabay"))
        assertNull(cinemaBayVersion("0.1.4-beta"));assertNull(cinemaBayVersion("../../1.2.3"))
    }
    @Test fun downloadsAreBoundToOwnHttpsRepository() {
        assertTrue(cinemaBayReleaseUrl("https://github.com/EmbyBenutzer/CinemaBay-TV/releases/download/v0.1.4/CinemaBay-0.1.4.apk"))
        for(url in listOf("http://github.com/EmbyBenutzer/CinemaBay-TV/releases/download/v1/a.apk", "https://github.com/attacker/CinemaBay-TV/releases/download/v1/a.apk", "https://github.com.evil.test/EmbyBenutzer/CinemaBay-TV/releases/download/v1/a.apk", "https://user:pass@github.com/EmbyBenutzer/CinemaBay-TV/releases/download/v1/a.apk"))assertFalse(cinemaBayReleaseUrl(url))
    }
    @Test fun ignoresDraftsAndPreReleasesAndSortsNumerically() {
        val json=JSONArray().put(release("v0.1.4",draft=true)).put(release("v0.1.5",preview=true)).put(release("v0.1.2")).put(release("v0.1.10"))
        assertEquals(listOf("0.1.10","0.1.2"),cinemaBayParseReleases(json.toString()).map{it.version})
    }
    @Test fun missingOrInvalidDigestsNeverProduceDownload() {
        for(value in listOf("","md5:"+"a".repeat(32),"sha256:xyz")) {
            val r=cinemaBayParseReleases(JSONArray().put(release(digest=value)).toString()).single()
            assertNull(r.url);assertEquals("Neue Funktionen",r.notes)
        }
    }
    @Test fun untrustedAssetsDoNotBecomeInstallable() {
        val r=cinemaBayParseReleases(JSONArray().put(release(url="https://evil.test/update.apk")).toString()).single()
        assertNull(r.url)
        val huge=release();huge.getJSONArray("assets").getJSONObject(0).put("size",150_000_001)
        assertNull(cinemaBayParseReleases(JSONArray().put(huge).toString()).single().url)
    }
    @Test fun exactUniversalApkNameAndUploadedStateAreRequired() {
        val json=release();json.getJSONArray("assets").getJSONObject(0).put("name","different-app.apk")
        assertNull(cinemaBayParseReleases(JSONArray().put(json).toString()).single().url)
        val valid=cinemaBayParseReleases(JSONArray().put(release()).toString()).single()
        assertNotNull(valid.url);assertEquals("a".repeat(64),valid.sha256)
    }
    @Test fun changelogIsBoundedAndOlderVersionsRemainAvailable() {
        val r=release();r.put("body","a".repeat(30_000))
        assertEquals(24_000,cinemaBayParseReleases(JSONArray().put(r).toString()).single().notes.length)
        assertTrue(cinemaBayBundledHistory.any{it.version=="0.0.3"})
        assertTrue(cinemaBayBundledHistory.any{it.version=="0.1.2"})
    }
}
