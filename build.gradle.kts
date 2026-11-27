import java.io.File
import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1" apply false
}

fun loadProps(file: File): Properties {
    val props = Properties()
    if (file.isFile) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

val localProps = loadProps(rootProject.file("local.properties"))
val siblingOpalDeskDir = File(rootProject.projectDir.parentFile, "OpalDesk")
val siblingProps = loadProps(File(siblingOpalDeskDir, "local.properties"))

fun resolveAndroidSdkDir(): File? {
    val candidates = listOfNotNull(
        localProps.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File),
        siblingProps.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::File),
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
        File("/Volumes/Su-SSD/dependencies/android-sdk"),
    )
    return candidates.firstOrNull { it.isDirectory }
}

val resolvedSdkDir = resolveAndroidSdkDir()
extra["opalDeskAndroidSdkDir"] = resolvedSdkDir?.absolutePath.orEmpty()

// Automatically share the exact same signing keystore as ../OpalDesk/opaldesk.p12 so
// OpalDesk's HelperClientBridge X.509 SHA-256 certificate pinning trusts this companion plugin.
val envKsFile = System.getenv("OPALDESK_KEYSTORE")?.takeIf { it.isNotBlank() }?.let(::File)
val localP12 = rootProject.file("opaldesk.p12")
val siblingP12 = File(siblingOpalDeskDir, "opaldesk.p12")
val activeKs: File? = when {
    envKsFile != null && envKsFile.isFile -> envKsFile
    localP12.isFile -> localP12
    siblingP12.isFile -> siblingP12
    else -> null
}

val envPass = System.getenv("OPALDESK_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
val localPass = localProps.getProperty("opaldesk.keystore.password")?.trim()?.takeIf { it.isNotBlank() }
val siblingPass = siblingProps.getProperty("opaldesk.keystore.password")?.trim()?.takeIf { it.isNotBlank() }
var resolvedPass = ""
if (activeKs != null) {
    for (cand in listOfNotNull(envPass, localPass, siblingPass, "opaldesk").distinct()) {
        try {
            val ks = KeyStore.getInstance("PKCS12")
            activeKs.inputStream().use { ks.load(it, cand.toCharArray()) }
            if (ks.getKey("opaldesk", cand.toCharArray()) != null) {
                resolvedPass = cand
                break
            }
        } catch (_: Throwable) {
        }
    }
}

extra["opalDeskSigningKeystorePath"] = activeKs?.absolutePath.orEmpty()
extra["opalDeskSigningPassword"] = resolvedPass
extra["opalDeskSigningAlias"] = "opaldesk"
