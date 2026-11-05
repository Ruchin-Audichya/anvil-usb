package dev.ruchin.anvil.devcli

import dev.ruchin.anvil.catalog.windows.MicrosoftIsoResolver
import dev.ruchin.anvil.catalog.windows.WindowsProduct
import dev.ruchin.anvil.common.ByteSize
import dev.ruchin.anvil.common.DebugLog
import dev.ruchin.anvil.common.formatEta
import dev.ruchin.anvil.download.DownloadRequest
import dev.ruchin.anvil.download.ResumableDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    DebugLog.sink = { System.err.println(it) }
    when (args.firstOrNull()) {
        "download-windows" -> downloadWindows(args.drop(1))
        "inspect-iso" -> inspectIso(File(args[1]))
        "write-windows" -> writeWindows(args.drop(1))
        else -> {
            System.err.println("usage: anvil-dev download-windows <WINDOWS_11_X64|WINDOWS_10_X64|WINDOWS_11_ARM64> <language> <dest-dir>")
            exitProcess(2)
        }
    }
}

private fun downloadWindows(a: List<String>) = runBlocking {
    val product = WindowsProduct.valueOf(a.getOrElse(0) { "WINDOWS_11_X64" })
    val language = a.getOrElse(1) { "English" }
    val dir = File(a.getOrElse(2) { "." }).apply { mkdirs() }
    val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
    val resolver = MicrosoftIsoResolver(client)
    val link = resolver.resolveLatest(product, language)
    println("${link.productDisplayName} -> ${link.fileName} sha256=${link.sha256}")
    val dest = File(dir, link.fileName)
    var last = 0L
    ResumableDownloader(client).download(
        DownloadRequest({ refresh -> if (refresh) resolver.resolveLatest(product, language).url else link.url }, dest, link.sha256),
    ) { p ->
        val now = System.currentTimeMillis()
        if (now - last > 10_000 || p.stage.name == "DONE") {
            last = now
            println("${p.stage} ${"%.1f".format(p.fraction * 100)}% ${ByteSize.format(p.bytesDone)}/${ByteSize.format(p.bytesTotal)} ${ByteSize.formatRate(p.bytesPerSecond)} eta ${formatEta(p.etaSeconds)}")
        }
    }
    println("OK ${dest.absolutePath}")
}

private fun inspectIso(iso: File) {
    dev.ruchin.anvil.common.ChannelSource.open(iso).use { src ->
        val t0 = System.nanoTime()
        val udf = dev.ruchin.anvil.udf.UdfReader(src)
        val root = udf.readRoot()
        val files = root.files().toList()
        println("volume='${udf.volumeIdentifier}' files=${files.size} dirs=${root.walk().count { it is dev.ruchin.anvil.udf.UdfNode.Directory }} bytes=${files.sumOf { it.size }} parsed in ${(System.nanoTime() - t0) / 1_000_000} ms")
        files.sortedByDescending { it.size }.take(5).forEach { println("  ${ByteSize.format(it.size)}  ${it.path}  (${it.content.segments.size} extents)") }
        val listing = File(iso.path + ".udf-listing.txt")
        listing.writeText(files.joinToString("\n") { "${it.size} ${it.path.trimStart('/')}" })
        println("listing -> ${listing.path}")
        val wim = root.find("sources/install.wim") as? dev.ruchin.anvil.udf.UdfNode.File
        if (wim != null) {
            val parts = dev.ruchin.anvil.wim.WimSplitter.split(wim.content)
            val h = dev.ruchin.anvil.wim.WimHeader(wim.content.asSource().readBytes(0, 208))
            println("install.wim: ${ByteSize.format(wim.size)} images=${h.imageCount} chunk=${h.chunkSize} flags=0x%x needsSplit=${dev.ruchin.anvil.wim.WimSplitter.needsSplit(wim.size)}".format(h.flags))
            parts.forEach { println("  ${it.fileName}: ${it.content.size} bytes, ${it.content.segments.size} segments") }
        }
    }
}

/** write-windows <iso> <image-file> [sizeBytes=16000000000] [gpt|mbr] */
private fun writeWindows(a: List<String>) = runBlocking {
    val iso = File(a[0])
    val img = File(a[1])
    val size = a.getOrNull(2)?.toLong() ?: 16_000_000_000L
    val scheme = if (a.getOrNull(3) == "mbr") dev.ruchin.anvil.fat.PartitionScheme.MBR else dev.ruchin.anvil.fat.PartitionScheme.GPT
    val label = iso.inputStream().use { it.readNBytes(dev.ruchin.anvil.image.ImageInspector.HEADER_BYTES) }
        .let { dev.ruchin.anvil.image.ImageInspector.inspect(it) }.volumeLabel
    println("ISO label=$label -> $img (${ByteSize.format(size)}, $scheme)")
    dev.ruchin.anvil.common.ChannelSource.open(iso).use { src ->
        dev.ruchin.anvil.blockdev.FileBlockDevice(img, size).use { usb ->
            var lastStage: dev.ruchin.anvil.writer.WindowsStage? = null
            var last = 0L
            val result = dev.ruchin.anvil.writer.WindowsUsbWriter().write(src, label, usb, dev.ruchin.anvil.writer.WindowsWriteOptions(scheme = scheme)) { p ->
                val now = System.currentTimeMillis()
                if (p.stage != lastStage || now - last > 5000) {
                    lastStage = p.stage; last = now
                    println("${p.stage} ${if (p.bytesTotal > 0) "%.1f%%".format(p.bytesDone * 100.0 / p.bytesTotal) else ""} ${ByteSize.formatRate(p.bytesPerSecond)} ${p.currentFile ?: ""}")
                }
            }
            println("OK $result")
        }
    }
}
