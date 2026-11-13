package com.bootforge.android

import android.app.*
import android.content.*
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.provider.OpenableColumns
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var manager: UsbManager
    private lateinit var imageText: TextView
    private lateinit var usbText: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var write: Button
    private var imageUri: Uri? = null
    private var imageName = ""
    private var selected: UsbDevice? = null
    private val busy = AtomicBoolean(false)
    private val permissionAction = "com.bootforge.android.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) {
                selected = d
                usbText.text = deviceDescription(d) + "\nPermission granted"
                say("USB drive selected. Check its identity and capacity before writing.")
            } else say("USB permission was denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(permissionReceiver, IntentFilter(permissionAction))
        makeUi()
        refreshUsb()
    }

    private fun makeUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 26, 24, 24)
            setBackgroundColor(Color.rgb(13, 19, 30))
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        page.addView(scroll)
        body.addView(TextView(this).apply {
            text = "BOOTFORGE"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        body.addView(TextView(this).apply {
            text = "Android USB OTG image writer"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 20)
        })
        body.addView(heading("1. SELECT ISO / DISK IMAGE"))
        imageText = line("No image selected")
        body.addView(imageText)
        body.addView(button("CHOOSE IMAGE") { chooseImage() })
        body.addView(heading("2. SELECT USB DRIVE"))
        usbText = line("No USB drive detected")
        body.addView(usbText)
        body.addView(button("REFRESH USB DEVICES") { refreshUsb() })
        body.addView(button("REQUEST USB PERMISSION") { requestUsbPermission() })
        body.addView(heading("3. BEFORE WRITING"))
        body.addView(TextView(this).apply {
            text = "WARNING: Writing destroys all existing data and partitions on the selected USB drive. Back up your USB first. Confirm the selected drive carefully. Do not disconnect it during writing."
            textSize = 14f
            setTextColor(Color.rgb(255, 190, 115))
            setPadding(0, 4, 0, 12)
        })
        body.addView(TextView(this).apply {
            text = "Compatibility: this first version writes raw/hybrid disk images, commonly used by Linux distributions. A standard Windows installation ISO is not made bootable by raw-writing its ISO file; Windows installer preparation requires a separate partition/filesystem workflow and is not included yet."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 12)
        })
        val confirm = CheckBox(this).apply {
            text = "I understand the selected USB will be erased."
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        body.addView(confirm)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        body.addView(bar, LinearLayout.LayoutParams(-1, 14).apply { topMargin = 16; bottomMargin = 8 })
        status = line("Ready")
        body.addView(status)
        write = button("WRITE IMAGE TO USB") {
            if (!confirm.isChecked) say("Confirm that the USB drive will be erased.")
            else beginWrite()
        }
        write.setBackgroundColor(Color.rgb(170, 48, 48))
        body.addView(write)
        body.addView(TextView(this).apply {
            text = "BootForge 1.0 • Android 12 target • Java/Kotlin 17"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 20, 0, 0)
        })
        setContentView(page)
    }

    private fun heading(s: String) = TextView(this).apply {
        text = s; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(115, 175, 255)); setPadding(0, 18, 0, 8)
    }
    private fun line(s: String) = TextView(this).apply {
        text = s; textSize = 14f; setTextColor(Color.WHITE); setPadding(0, 6, 0, 10)
    }
    private fun button(s: String, f: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 }
    }
    private fun say(s: String) { status.text = s }

    private fun chooseImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(i, 51)
    }

    @Deprecated("Legacy callback for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 51 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            imageUri = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            imageName = queryName(uri) ?: "Selected image"
            imageText.text = imageName + "\n" + humanSize(querySize(uri))
            say("Image selected. Connect a USB OTG drive.")
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }
    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
        return -1
    }
    private fun humanSize(n: Long): String {
        if (n < 0) return "Size unavailable"
        var x = n.toDouble(); val u = arrayOf("B", "KB", "MB", "GB", "TB"); var i = 0
        while (x >= 1024 && i < u.lastIndex) { x /= 1024; i++ }
        return String.format("%.2f %s", x, u[i])
    }

    private fun massStorage(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val x = d.getInterface(i)
            if (x.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) return x
        }
        return null
    }
    private fun deviceDescription(d: UsbDevice) =
        "USB: ${d.deviceName}\nVID ${d.vendorId} • PID ${d.productId}\nInterface: ${massStorage(d)?.id ?: "unknown"}"

    private fun refreshUsb() {
        val devices = manager.deviceList.values.filter { massStorage(it) != null }
        selected = devices.firstOrNull()
        usbText.text = if (devices.isEmpty()) "No USB mass-storage device found. Connect an OTG adapter and USB drive, then refresh."
            else devices.joinToString("\n\n") { deviceDescription(it) }
        say("${devices.size} compatible USB device(s) detected.")
    }

    private fun requestUsbPermission() {
        val d = selected ?: manager.deviceList.values.firstOrNull { massStorage(it) != null }
        if (d == null) { say("Connect a USB mass-storage drive first."); return }
        selected = d
        if (manager.hasPermission(d)) {
            usbText.text = deviceDescription(d) + "\nPermission granted"
            say("USB permission already granted.")
        } else {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(d, pi)
            say("Approve the USB permission dialog.")
        }
    }

    private fun beginWrite() {
        val uri = imageUri ?: run { say("Choose an image first."); return }
        val dev = selected ?: run { say("Select a USB drive first."); return }
        if (!manager.hasPermission(dev)) { requestUsbPermission(); return }
        if (!busy.compareAndSet(false, true)) return
        write.isEnabled = false
        bar.progress = 0
        thread(name = "BootForgeWriter") {
            try {
                val size = querySize(uri)
                require(size > 0) { "Image size is unavailable." }
                val conn = manager.openDevice(dev) ?: error("Unable to open USB device.")
                try {
                    val bot = BulkOnlyTransport(conn, dev)
                    bot.open()
                    val cap = bot.capacity()
                    require(size <= cap.blockCount * cap.blockSize) {
                        "Image is larger than USB drive. Image ${humanSize(size)}, USB ${humanSize(cap.blockCount * cap.blockSize)}"
                    }
                    runOnUiThread { say("Writing… Do not disconnect the USB drive.") }
                    contentResolver.openInputStream(uri)?.use { input ->
                        val chunkBlocks = maxOf(1, minOf(32, 16384 / cap.blockSize))
                        val chunkBytes = chunkBlocks * cap.blockSize
                        val buffer = ByteArray(chunkBytes)
                        var written = 0L
                        var lba = 0L
                        while (written < size) {
                            buffer.fill(0)
                            val wanted = minOf(chunkBytes.toLong(), size - written).toInt()
                            readExact(input, buffer, wanted)
                            val blocks = (wanted + cap.blockSize - 1) / cap.blockSize
                            val payload = buffer.copyOf(blocks * cap.blockSize)
                            bot.write(lba, blocks, payload, cap.blockSize)
                            lba += blocks
                            written += wanted
                            val percent = (written * 100 / size).toInt().coerceIn(0, 100)
                            runOnUiThread { bar.progress = percent; status.text = "Writing $percent% — ${humanSize(written)} / ${humanSize(size)}" }
                        }
                    } ?: error("Cannot open selected image.")
                    bot.sync()
                    runOnUiThread { bar.progress = 100; say("Write completed. Safely eject the USB drive before unplugging.") }
                } finally { conn.close() }
            } catch (e: Exception) {
                runOnUiThread { say("Write failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                busy.set(false)
                runOnUiThread { write.isEnabled = true }
            }
        }
    }

    private fun readExact(input: InputStream, b: ByteArray, n: Int) {
        var p = 0
        while (p < n) {
            val r = input.read(b, p, n - p)
            if (r < 0) error("Image ended unexpectedly.")
            if (r > 0) p += r
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private class BulkOnlyTransport(private val conn: UsbDeviceConnection, device: UsbDevice) {
        private val intf: UsbInterface
        private val input: UsbEndpoint
        private val output: UsbEndpoint
        private var tag = 1
        init {
            intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
                ?: error("USB Mass Storage interface not found.")
            input = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
                ?: error("Bulk IN endpoint not found.")
            output = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                ?: error("Bulk OUT endpoint not found.")
        }
        fun open() {
            check(conn.claimInterface(intf, true)) { "Could not claim USB interface." }
            command(byteArrayOf(0x12, 0, 0, 0, 36, 0), 36, true)
        }
        data class Capacity(val blockCount: Long, val blockSize: Int)
        fun capacity(): Capacity {
            val b = ByteBuffer.wrap(command(byteArrayOf(0x25,0,0,0,0,0,0,0,0,0), 8, true)).order(ByteOrder.BIG_ENDIAN)
            val last = b.int.toLong() and 0xffffffffL
            val block = b.int
            require(block in 512..4096) { "Unsupported USB sector size: $block" }
            return Capacity(last + 1, block)
        }
        fun write(lba: Long, blocks: Int, payload: ByteArray, blockSize: Int) {
            require(lba <= 0xffffffffL && blocks in 1..0xffff)
            require(payload.size == blocks * blockSize)
            val cdb = ByteArray(10)
            cdb[0] = 0x2A
            cdb[2] = (lba shr 24).toByte(); cdb[3] = (lba shr 16).toByte()
            cdb[4] = (lba shr 8).toByte(); cdb[5] = lba.toByte()
            cdb[7] = (blocks shr 8).toByte(); cdb[8] = blocks.toByte()
            command(cdb, payload.size, false, payload)
        }
        fun sync() {
            command(byteArrayOf(0x35,0,0,0,0,0,0,0,0,0), 0, false)
        }
        private fun command(cdb: ByteArray, length: Int, fromDevice: Boolean, payload: ByteArray? = null): ByteArray {
            val currentTag = tag++
            val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            cbw.putInt(0x43425355); cbw.putInt(currentTag); cbw.putInt(length)
            cbw.put(if (fromDevice) 0x80.toByte() else 0); cbw.put(0); cbw.put(cdb.size.toByte())
            cbw.put(cdb.copyOf(16))
            transferOut(cbw.array(), 31)
            val result = if (length > 0 && fromDevice) ByteArray(length) else ByteArray(0)
            if (length > 0) {
                if (fromDevice) transferIn(result, length)
                else transferOut(payload ?: error("Missing payload"), length)
            }
            val csw = ByteArray(13)
            transferIn(csw, 13)
            val c = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
            val sig = c.int; val returned = c.int; c.int
            val status = c.get().toInt() and 255
            check(sig == 0x53425355 && returned == currentTag) { "Invalid USB transport response." }
            check(status == 0) { "USB device rejected command (status $status)." }
            return result
        }
        private fun transferOut(b: ByteArray, length: Int) {
            var p = 0
            while (p < length) {
                val n = conn.bulkTransfer(output, b, p, length - p, 20000)
                check(n > 0) { "USB bulk write failed." }; p += n
            }
        }
        private fun transferIn(b: ByteArray, length: Int) {
            var p = 0
            while (p < length) {
                val n = conn.bulkTransfer(input, b, p, length - p, 20000)
                check(n > 0) { "USB bulk read failed." }; p += n
            }
        }
    }
}
