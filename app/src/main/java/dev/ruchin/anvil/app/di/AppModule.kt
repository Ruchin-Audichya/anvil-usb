package dev.ruchin.anvil.app.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ruchin.anvil.catalog.linux.LinuxIsoResolver
import dev.ruchin.anvil.catalog.linux.LinuxManifestSource
import dev.ruchin.anvil.catalog.linux.PinnedKeys
import dev.ruchin.anvil.catalog.windows.MicrosoftIsoResolver
import dev.ruchin.anvil.download.ResumableDownloader
import dev.ruchin.anvil.writer.RawImageWriter
import dev.ruchin.anvil.writer.WindowsUsbWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun rawImageWriter(): RawImageWriter = RawImageWriter()

    @Provides @Singleton
    fun windowsWriter(): WindowsUsbWriter = WindowsUsbWriter()

    @Provides @Singleton
    fun downloader(client: OkHttpClient): ResumableDownloader = ResumableDownloader(client)

    @Provides @Singleton
    fun msResolver(client: OkHttpClient): MicrosoftIsoResolver = MicrosoftIsoResolver(client)

    @Provides @Singleton
    fun pinnedKeys(): PinnedKeys = PinnedKeys.load()

    @Provides @Singleton
    fun linuxManifestSource(@ApplicationContext context: Context, client: OkHttpClient, keys: PinnedKeys): LinuxManifestSource =
        LinuxManifestSource(client, bundled = { context.assets.open("linux-manifest.json").bufferedReader().use { it.readText() } }, keys = keys)

    @Provides @Singleton
    fun linuxResolver(client: OkHttpClient, keys: PinnedKeys): LinuxIsoResolver = LinuxIsoResolver(client, keys)
}
