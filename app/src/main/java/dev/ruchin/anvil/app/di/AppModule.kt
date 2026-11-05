package dev.ruchin.anvil.app.di

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
}
