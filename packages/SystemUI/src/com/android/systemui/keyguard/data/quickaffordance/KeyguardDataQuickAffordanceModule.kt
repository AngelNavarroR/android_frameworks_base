/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ElementsIntoSet

@Module
interface KeyguardDataQuickAffordanceModule {
    @Binds
    fun providerClientFactory(
        impl: KeyguardQuickAffordanceProviderClientFactoryImpl
    ): KeyguardQuickAffordanceProviderClientFactory

    companion object {
        @Provides
        @ElementsIntoSet
        fun quickAffordanceConfigs(
            assistant: AssistantKeyguardQuickAffordanceConfig,
            camera: CameraQuickAffordanceConfig,
            doNotDisturb: DoNotDisturbQuickAffordanceConfig,
            flashlight: FlashlightQuickAffordanceConfig,
            home: HomeControlsKeyguardQuickAffordanceConfig,
            mute: MuteQuickAffordanceConfig,
            // blossom: Wallet FUERA. Este equipo no tiene NFC (removido en la ROM) ni Google
            // Pay, asi que ningun servicio de wallet responde. Medido el 18 ago: erroraba
            // "Wallet card retrieval error" 237 veces en 20 h. Dagger construye TODOS los
            // configs de este Set aunque el acceso no este en config_keyguardQuickAffordanceDefaults,
            // asi que quitarlo del array no bastaba: hay que sacarlo de aqui.
            // quickAccessWallet: QuickAccessWalletKeyguardQuickAffordanceConfig,
            qrCodeScanner: QrCodeScannerKeyguardQuickAffordanceConfig,
            videoCamera: VideoCameraQuickAffordanceConfig,
        ): Set<KeyguardQuickAffordanceConfig> {
            return setOf(
                assistant,
                camera,
                doNotDisturb,
                flashlight,
                home,
                mute,
                // quickAccessWallet,   // blossom: ver arriba
                qrCodeScanner,
                videoCamera,
            )
        }
    }
}
