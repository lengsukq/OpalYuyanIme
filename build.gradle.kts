import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1" apply false
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { b -> "%02x".format(b) }

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
    val userHome = System.getProperty("user.home").orEmpty()
    val candidates = listOfNotNull(
        localProps.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File),
        siblingProps.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File),
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::File),
        File("/Volumes/Su-SSD/dependencies/android-sdk"),
        userHome.takeIf { it.isNotBlank() }?.let { File(it, "Library/Android/sdk") },
        userHome.takeIf { it.isNotBlank() }?.let { File(it, "Android/Sdk") },
    )
    return candidates.firstOrNull { it.isDirectory }
}

val resolvedSdkDir = resolveAndroidSdkDir()
extra["opalDeskAndroidSdkDir"] = resolvedSdkDir?.absolutePath.orEmpty()

// Share the exact same signing certificate as OpalDesk (opaldesk.p12, alias "opaldesk") so
// OpalDesk's HelperClientBridge X.509 SHA-256 certificate pinning trusts this companion plugin.
val expectedCertOverride = (
    project.findProperty("expectedReleaseCertSha256")?.toString()?.takeIf { it.isNotBlank() }
        ?: System.getenv("OPALDESK_EXPECTED_CERT_SHA256")?.takeIf { it.isNotBlank() }
    )?.trim()?.lowercase()
val allowCustomReleaseCert =
    project.findProperty("allowCustomReleaseCert")?.toString()?.equals("true", ignoreCase = true) == true

val envKsPath = System.getenv("OPALDESK_KEYSTORE")?.takeIf { it.isNotBlank() }
val envKsFile = envKsPath?.let { rootProject.file(it) }
if (envKsFile != null && !envKsFile.isFile) {
    throw GradleException("OPALDESK_KEYSTORE points to non-existent file: ${envKsFile.absolutePath}")
}

val envPass = System.getenv("OPALDESK_KEYSTORE_PASS")?.takeIf { it.isNotBlank() }
if (envKsFile != null && envPass == null) {
    throw GradleException("OPALDESK_KEYSTORE_PASS must be explicitly set when OPALDESK_KEYSTORE is provided.")
}

val localP12 = rootProject.file("opaldesk.p12")
val siblingP12 = File(siblingOpalDeskDir, "opaldesk.p12")
val activeKs: File? = when {
    envKsFile != null -> envKsFile
    localP12.isFile -> localP12
    siblingP12.isFile -> siblingP12
    else -> null
}

val localPass = localProps.getProperty("opaldesk.keystore.password")?.trim()?.takeIf { it.isNotBlank() }
val siblingPass = siblingProps.getProperty("opaldesk.keystore.password")?.trim()?.takeIf { it.isNotBlank() }

var resolvedPass = ""
var resolvedAlias = "opaldesk"
var resolvedSource = "none"
var resolvedCertSha256 = ""

if (activeKs != null) {
    val candidatePasswords = if (envKsFile != null) {
        listOfNotNull(envPass)
    } else {
        listOfNotNull(envPass, localPass, siblingPass, "opaldesk").distinct()
    }
    var loadedKs: KeyStore? = null
    var unlockedPass: String? = null
    var lastErr: Throwable? = null
    for (cand in candidatePasswords) {
        try {
            val ks = KeyStore.getInstance("PKCS12")
            activeKs.inputStream().use { ks.load(it, cand.toCharArray()) }
            if (ks.getKey(resolvedAlias, cand.toCharArray()) != null) {
                loadedKs = ks
                unlockedPass = cand
                break
            }
        } catch (t: Throwable) {
            lastErr = t
        }
    }
    val ks = loadedKs ?: throw GradleException(
        "Failed to unlock release keystore (${activeKs.name}) with configured keystore password.",
        lastErr,
    )
    val cert = ks.getCertificate(resolvedAlias)
        ?: throw GradleException("Alias '$resolvedAlias' not found in release keystore: ${activeKs.absolutePath}")
    val actualSha256 = sha256Hex(cert.encoded)
    if (expectedCertOverride != null && !allowCustomReleaseCert && !actualSha256.equals(expectedCertOverride, ignoreCase = true)) {
        throw GradleException(
            "Release keystore certificate SHA-256 mismatch! Expected $expectedCertOverride, got $actualSha256"
        )
    }
    resolvedPass = unlockedPass ?: envPass.orEmpty()
    resolvedSource = when {
        envKsFile != null -> "OPALDESK_KEYSTORE"
        activeKs == localP12 -> "opaldesk.p12"
        else -> "../OpalDesk/opaldesk.p12"
    }
    resolvedCertSha256 = actualSha256
}

extra["opalDeskSigningKeystorePath"] = activeKs?.absolutePath.orEmpty()
extra["opalDeskSigningPassword"] = resolvedPass
extra["opalDeskSigningAlias"] = resolvedAlias
extra["opalDeskSigningSource"] = resolvedSource
extra["opalDeskCertSha256"] = resolvedCertSha256
extra["opalDeskExpectedCertSha256"] = expectedCertOverride.orEmpty()
