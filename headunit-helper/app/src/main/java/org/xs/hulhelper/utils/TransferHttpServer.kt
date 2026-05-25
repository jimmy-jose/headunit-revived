package org.xs.hulhelper.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

object TransferHttpServer {
    const val PORT = 8787

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var serverThread: Thread? = null

    fun start(context: Context) {
        synchronized(this) {
            if (serverThread?.isAlive == true) return

            val appContext = context.applicationContext
            getTransferDirectory(appContext).mkdirs()

            val socket = try {
                ServerSocket(PORT).apply { reuseAddress = true }
            } catch (e: Exception) {
                AppLog.e("Transfer server failed to start: ${e.message}")
                return
            }

            serverSocket = socket
            serverThread = thread(
                name = "hul-helper-transfer-server",
                isDaemon = true
            ) {
                AppLog.i("Transfer server started on port $PORT")
                while (!Thread.currentThread().isInterrupted) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handleClient(appContext, client)
                }
                AppLog.i("Transfer server stopped")
            }
        }
    }

    fun stop() {
        synchronized(this) {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
            serverThread?.interrupt()
            serverThread = null
        }
    }

    fun isRunning(): Boolean = serverThread?.isAlive == true

    fun getLocalUrl(context: Context): String? {
        val host = resolveHostAddress(context) ?: return null
        return "http://$host:$PORT/"
    }

    fun getTransferDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(baseDir, "transfer")
    }

    fun listTransferFiles(context: Context): List<File> {
        return getTransferDirectory(context)
            .listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun importFile(context: Context, uri: Uri): File {
        val resolver = context.contentResolver
        val originalName = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "transfer.bin"
        val safeName = sanitizeFileName(originalName)
        val destination = uniqueFile(getTransferDirectory(context), safeName)
        destination.parentFile?.mkdirs()

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to open selected file")

        return destination
    }

    private fun handleClient(context: Context, socket: Socket) {
        thread(name = "hul-helper-transfer-client", isDaemon = true) {
            socket.use { client ->
                try {
                    val input = BufferedInputStream(client.getInputStream())
                    val output = BufferedOutputStream(client.getOutputStream())
                    val requestLine = readHttpLine(input) ?: return@use
                    if (requestLine.isBlank()) return@use

                    val requestParts = requestLine.split(" ")
                    if (requestParts.size < 2) {
                        sendText(output, 400, "Bad Request", "Malformed request line.")
                        return@use
                    }

                    val method = requestParts[0].uppercase(Locale.US)
                    val target = requestParts[1]
                    val headers = readHeaders(input)

                    when {
                        method == "GET" && (target == "/" || target.startsWith("/?")) -> {
                            sendHtml(output, buildIndexPage(context))
                        }
                        method == "GET" && target.startsWith("/download") -> {
                            handleDownload(context, output, target)
                        }
                        method == "GET" && target.startsWith("/delete") -> {
                            handleDelete(context, output, target)
                        }
                        method == "POST" && target.startsWith("/upload") -> {
                            handleUpload(context, input, output, target, headers)
                        }
                        else -> sendText(output, 404, "Not Found", "Unknown route.")
                    }
                } catch (e: Exception) {
                    AppLog.e("Transfer request failed: ${e.message}")
                }
            }
        }
    }

    private fun handleDownload(context: Context, output: BufferedOutputStream, target: String) {
        val fileName = parseQuery(target)["name"]?.let(::sanitizeFileName).orEmpty()
        if (fileName.isBlank()) {
            sendText(output, 400, "Bad Request", "Missing file name.")
            return
        }

        val file = File(getTransferDirectory(context), fileName)
        if (!file.exists() || !file.isFile) {
            sendText(output, 404, "Not Found", "File not found.")
            return
        }

        sendFile(output, file)
    }

    private fun handleDelete(context: Context, output: BufferedOutputStream, target: String) {
        val fileName = parseQuery(target)["name"]?.let(::sanitizeFileName).orEmpty()
        if (fileName.isBlank()) {
            sendRedirect(output, "/")
            return
        }

        val file = File(getTransferDirectory(context), fileName)
        if (file.exists()) {
            file.delete()
        }
        sendRedirect(output, "/")
    }

    private fun handleUpload(
        context: Context,
        input: InputStream,
        output: BufferedOutputStream,
        target: String,
        headers: Map<String, String>
    ) {
        val fileName = parseQuery(target)["name"]?.let(::sanitizeFileName).orEmpty()
        if (fileName.isBlank()) {
            sendText(output, 400, "Bad Request", "Missing file name.")
            return
        }

        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength <= 0L) {
            sendText(output, 411, "Length Required", "Upload body is missing.")
            return
        }

        val destination = uniqueFile(getTransferDirectory(context), fileName)
        val tempFile = File(destination.parentFile, "${destination.name}.part")
        tempFile.parentFile?.mkdirs()

        FileOutputStream(tempFile).use { fileOut ->
            copyExactly(input, fileOut, contentLength)
        }

        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }

        AppLog.i("Transfer upload saved: ${destination.name} (${destination.length()} bytes)")
        sendText(output, 200, "OK", "Uploaded ${destination.name}")
    }

    private fun buildIndexPage(context: Context): String {
        val fileItems = listTransferFiles(context)
            .joinToString(separator = "\n") { file ->
                val encodedName = urlEncode(file.name)
                """
                <div class="file-row">
                  <div class="file-meta">
                    <strong>${escapeHtml(file.name)}</strong>
                    <span>${formatSize(file.length())} • ${formatDate(file.lastModified())}</span>
                  </div>
                  <div class="file-actions">
                    <a href="/download?name=$encodedName">Download</a>
                    <a class="danger" href="/delete?name=$encodedName">Delete</a>
                  </div>
                </div>
                """.trimIndent()
            }
            .ifBlank { """<p class="empty">No files yet. Add an APK from the phone, or upload logs from the head unit.</p>""" }

        val url = getLocalUrl(context) ?: "Waiting for hotspot or Wi-Fi..."

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>HUL Transfer</title>
              <style>
                :root {
                  color-scheme: dark;
                  --bg: #10161b;
                  --panel: #182228;
                  --panel-2: #20303a;
                  --text: #f2f7f9;
                  --muted: #9cb0b8;
                  --accent: #31c4b2;
                  --danger: #ef6b73;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  font-family: sans-serif;
                  background: linear-gradient(180deg, #0e1519, #17232c 55%, #0f171d);
                  color: var(--text);
                }
                main {
                  max-width: 780px;
                  margin: 0 auto;
                  padding: 24px 16px 40px;
                }
                .hero, .panel {
                  background: rgba(24, 34, 40, 0.94);
                  border: 1px solid rgba(156, 176, 184, 0.18);
                  border-radius: 22px;
                  padding: 18px;
                  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.2);
                }
                .hero h1, .panel h2 {
                  margin: 0 0 10px;
                }
                .hero p, .panel p {
                  margin: 0;
                  color: var(--muted);
                }
                .url {
                  display: block;
                  margin-top: 12px;
                  padding: 12px 14px;
                  border-radius: 14px;
                  background: var(--panel-2);
                  color: var(--text);
                  font-weight: 700;
                  word-break: break-all;
                }
                .stack { display: grid; gap: 16px; margin-top: 18px; }
                .upload-box {
                  margin-top: 14px;
                  padding: 14px;
                  border-radius: 16px;
                  background: var(--panel-2);
                }
                input[type=file] {
                  width: 100%;
                  color: var(--text);
                }
                button {
                  border: 0;
                  border-radius: 14px;
                  background: var(--accent);
                  color: #0b1115;
                  padding: 12px 16px;
                  font-size: 15px;
                  font-weight: 700;
                  margin-top: 12px;
                }
                .status {
                  margin-top: 10px;
                  color: var(--muted);
                  min-height: 20px;
                }
                .file-row {
                  display: flex;
                  justify-content: space-between;
                  gap: 12px;
                  padding: 12px 0;
                  border-top: 1px solid rgba(156, 176, 184, 0.12);
                }
                .file-row:first-of-type { border-top: 0; }
                .file-meta { display: grid; gap: 4px; }
                .file-meta span { color: var(--muted); font-size: 13px; }
                .file-actions {
                  display: flex;
                  gap: 10px;
                  align-items: center;
                  flex-wrap: wrap;
                }
                a {
                  color: var(--accent);
                  text-decoration: none;
                  font-weight: 700;
                }
                a.danger { color: var(--danger); }
                .empty {
                  margin-top: 14px;
                }
              </style>
            </head>
            <body>
              <main>
                <section class="hero">
                  <h1>HUL Transfer Center</h1>
                  <p>Use this page to download APKs from the phone or upload logs from the head unit while both devices are on the same hotspot.</p>
                  <span class="url">$url</span>
                </section>

                <div class="stack">
                  <section class="panel">
                    <h2>Upload from this device</h2>
                    <p>Select one file at a time. Uploads are saved to the phone’s Helper transfer folder.</p>
                    <div class="upload-box">
                      <input id="fileInput" type="file" />
                      <button id="uploadButton" type="button">Upload File</button>
                      <div id="uploadStatus" class="status"></div>
                    </div>
                  </section>

                  <section class="panel">
                    <h2>Files ready for download</h2>
                    $fileItems
                  </section>
                </div>
              </main>
              <script>
                const fileInput = document.getElementById('fileInput');
                const uploadButton = document.getElementById('uploadButton');
                const uploadStatus = document.getElementById('uploadStatus');

                uploadButton.addEventListener('click', async () => {
                  const file = fileInput.files && fileInput.files[0];
                  if (!file) {
                    uploadStatus.textContent = 'Choose a file first.';
                    return;
                  }

                  uploadStatus.textContent = 'Uploading ' + file.name + '...';
                  try {
                    const response = await fetch('/upload?name=' + encodeURIComponent(file.name), {
                      method: 'POST',
                      headers: {
                        'Content-Type': 'application/octet-stream'
                      },
                      body: file
                    });
                    const text = await response.text();
                    if (!response.ok) {
                      throw new Error(text || 'Upload failed');
                    }
                    uploadStatus.textContent = text + ' Refreshing...';
                    window.location.reload();
                  } catch (error) {
                    uploadStatus.textContent = error.message || 'Upload failed.';
                  }
                });
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun sendHtml(output: BufferedOutputStream, html: String) {
        sendBytes(
            output = output,
            code = 200,
            message = "OK",
            contentType = "text/html; charset=utf-8",
            bytes = html.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendText(output: BufferedOutputStream, code: Int, message: String, body: String) {
        sendBytes(
            output = output,
            code = code,
            message = message,
            contentType = "text/plain; charset=utf-8",
            bytes = body.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendRedirect(output: BufferedOutputStream, location: String) {
        val bytes = ByteArray(0)
        output.write("HTTP/1.1 302 Found\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Location: $location\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun sendFile(output: BufferedOutputStream, file: File) {
        val mimeType = when (file.extension.lowercase(Locale.US)) {
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log" -> "text/plain; charset=utf-8"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        output.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Type: $mimeType\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Length: ${file.length()}\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(
            "Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n"
                .toByteArray(StandardCharsets.UTF_8)
        )
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { input -> input.copyTo(output) }
        output.flush()
    }

    private fun sendBytes(
        output: BufferedOutputStream,
        code: Int,
        message: String,
        contentType: String,
        bytes: ByteArray
    ) {
        output.write("HTTP/1.1 $code $message\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            headers[key] = value
        }
        return headers
    }

    private fun readHttpLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) {
                return if (buffer.isEmpty()) null else buffer.toString()
            }
            if (next == '\n'.code) {
                return buffer.toString().trimEnd('\r')
            }
            buffer.append(next.toChar())
        }
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, byteCount: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = byteCount
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) {
                throw IllegalStateException("Upload ended unexpectedly")
            }
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private fun resolveHostAddress(context: Context): String? {
        val preferredPrefixes = listOf("ap", "wlan", "swlan", "wifi", "rndis")
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        preferredPrefixes.forEach { prefix ->
            interfaces.firstOrNull { it.name.startsWith(prefix, ignoreCase = true) }
                ?.let(::ipv4AddressForInterface)
                ?.let { return it }
        }

        interfaces.forEach { networkInterface ->
            ipv4AddressForInterface(networkInterface)?.let { return it }
        }

        return null
    }

    private fun ipv4AddressForInterface(networkInterface: NetworkInterface): String? {
        if (!networkInterface.isUp || networkInterface.isLoopback) return null
        return networkInterface.inetAddresses
            .toList()
            .firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    address.hostAddress != null &&
                    address.isSiteLocalAddress
            }
            ?.hostAddress
    }

    private fun parseQuery(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = urlDecode(part.substring(0, separator))
                val value = urlDecode(part.substring(separator + 1))
                key to value
            }
            .toMap()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }

    private fun uniqueFile(directory: File, originalName: String): File {
        val safeName = sanitizeFileName(originalName)
        var candidate = File(directory, safeName)
        if (!candidate.exists()) return candidate

        val dotIndex = safeName.lastIndexOf('.')
        val base = if (dotIndex > 0) safeName.substring(0, dotIndex) else safeName
        val extension = if (dotIndex > 0) safeName.substring(dotIndex) else ""
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base-$index$extension")
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace("\\", "_")
            .replace("/", "_")
            .replace("..", ".")
            .trim()
            .ifBlank { "transfer.bin" }
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun formatSize(sizeBytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val formatter = DecimalFormat("0.#")
        return when {
            sizeBytes >= gb -> "${formatter.format(sizeBytes / gb)} GB"
            sizeBytes >= mb -> "${formatter.format(sizeBytes / mb)} MB"
            sizeBytes >= kb -> "${formatter.format(sizeBytes / kb)} KB"
            else -> "$sizeBytes B"
        }
    }
}
