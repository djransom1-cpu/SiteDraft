package com.example.sitedraft.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import com.example.sitedraft.cloud.CloudService
import com.example.sitedraft.cloud.UnavailableCloud
import kotlinx.coroutines.CoroutineDispatcher

/** Where project files live. Names are plain file names ("project_X.json"). */
interface ProjectStorage {
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun exists(name: String): Boolean
}

/** Small persistent key/value settings (palm rejection mode, recent colours, ...). */
interface KeyValuePrefs {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemoryStorage : ProjectStorage {
    private val files = HashMap<String, String>()
    override fun read(name: String) = files[name]
    override fun write(name: String, text: String) { files[name] = text }
    override fun exists(name: String) = files.containsKey(name)
}

class InMemoryPrefs : KeyValuePrefs {
    private val values = HashMap<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
}

/**
 * Everything the drawing core needs from the host that differs per platform. The Android app,
 * the desktop app, the web app and the iOS app each provide one; unit tests use [NoopPlatform].
 */
interface Platform {
    val name: String
    /** null = nothing is persisted (unit tests). */
    val storage: ProjectStorage?
    val prefs: KeyValuePrefs
    fun log(tag: String, message: String) {}
    /** A short non-blocking notice to the user. */
    fun toast(message: String) {}
    /**
     * Renders [draw] onto a [width] x [height] page and hands it to the user as a PDF where the
     * platform can make one, otherwise as an image. Returns a message for the user (where it
     * went) or null if nothing could be written.
     */
    fun exportPage(fileName: String, width: Int, height: Int, density: Float, draw: DrawScope.() -> Unit): String?
    /** Writes a text file (DXF) and returns a message for the user, or null if it could not. */
    fun exportText(fileName: String, text: String): String?
    /** The Bosch GLM Bluetooth client on platforms that have one; the Laser panel knows the type. */
    fun createLaserClient(): Any? = null
    val supportsLaser: Boolean get() = false
    /** Firebase sign-in and Firestore; [UnavailableCloud] where the host has none (unit tests). */
    val cloud: CloudService get() = UnavailableCloud
    /** Opens [url] in the system browser (Stripe Checkout). False if the platform cannot. */
    fun openUrl(url: String): Boolean = false
    /** Where Stripe sends the user after paying; null = the Cloud Function's default (the web app URL). */
    val checkoutReturnUrl: String? get() = null
}

/** Used by unit tests and as a safe default: nothing persists, nothing is exported. */
object NoopPlatform : Platform {
    override val name = "test"
    override val storage: ProjectStorage? = null
    override val prefs: KeyValuePrefs = InMemoryPrefs()
    override fun exportPage(fileName: String, width: Int, height: Int, density: Float, draw: DrawScope.() -> Unit): String? = null
    override fun exportText(fileName: String, text: String): String? = null
}

val LocalPlatform = staticCompositionLocalOf<Platform> { NoopPlatform }

/** Dispatcher for file I/O (Dispatchers.IO where it exists). */
expect val ioDispatcher: CoroutineDispatcher

/** PNG bytes for an image, for saving it inside a project file. */
expect fun encodePng(image: ImageBitmap): ByteArray

/** An image from PNG/JPEG bytes, or null if they cannot be decoded. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * Whether the pen's side button (or, with a mouse, the secondary button) is held during this
 * pointer event. Holding it while drawing erases, as in Samsung Notes.
 */
expect fun PointerEvent.stylusButtonHeld(): Boolean

/** Epoch milliseconds. */
expect fun nowMillis(): Long

/** A fresh random id. */
expect fun randomId(): String
