package dev.ruchin.anvil.catalog.linux

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Hits the real distro servers for every entry in the bundled manifest: downloads the checksum
 * list and signature, verifies them with the pinned key, then probes every mirror for the ISO.
 * Run with: ./gradlew :core:catalog:liveTest
 *
 * Fails if an entry can't be resolved at all. Individual mirror failures are printed in a
 * "MIRROR FAILURES" block (they're expected now and then) so they can be pruned from the manifest.
 */
@Tag("live")
class LinuxCatalogLiveTest {
    @Test fun `resolve every bundled Linux entry from real mirrors`() = runBlocking {
        assumeTrue(System.getProperty("anvil.live") == "true")
        val client = OkHttpClient()
        val manifest = LinuxManifest.parse(java.io.File(System.getProperty("anvil.linuxManifest")).readText())
        val resolver = LinuxIsoResolver(client)
        val prober = MirrorProber(client)
        val broken = mutableListOf<String>()
        val mirrorFailures = mutableListOf<String>()
        for (entry in manifest.entries) {
            try {
                val r = resolver.resolve(entry)
                println("${entry.id}: ${r.fileName} | ${r.sizeBytes} B | sha256=${r.sha256} | signed by ${r.signer.owner}")
                val dir = entry.isoPath.substringBeforeLast('/', "")
                val all = prober.probeAll(entry.mirrors.map { LinuxIsoResolver.join(it, "$dir/${r.fileName}") })
                all.forEach { println("   $it") }
                all.filterNot { it.ok }.forEach { mirrorFailures += "${entry.id}: $it" }
            } catch (e: Exception) {
                println("${entry.id}: FAILED ${e.message}")
                broken += "${entry.id}: ${e.message}"
            }
        }
        if (mirrorFailures.isNotEmpty()) {
            println("MIRROR FAILURES (${mirrorFailures.size}):")
            mirrorFailures.forEach { println("  $it") }
        }
        if (broken.isNotEmpty()) fail<Unit>("Unresolvable entries:\n" + broken.joinToString("\n"))
    }
}
