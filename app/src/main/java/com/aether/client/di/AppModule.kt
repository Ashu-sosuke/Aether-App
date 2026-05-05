package com.aether.client.di

import android.content.Context
import com.aether.client.data.datastore.SettingsDataStore
import com.aether.client.overlay.OverlayManager
import com.aether.client.websocket.AetherWebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext ctx: Context): SettingsDataStore =
        SettingsDataStore(ctx)

    @Provides
    @Singleton
    fun provideOverlayManager(@ApplicationContext ctx: Context): OverlayManager =
        OverlayManager(ctx)

    @Provides
    @Singleton
    fun provideAetherWebSocketClient(
        @ApplicationContext context: Context,
        overlayManager: OverlayManager,
        settingsDs: SettingsDataStore
    ): AetherWebSocketClient = AetherWebSocketClient(context, overlayManager, settingsDs)
}
