/*
 * Copyright (C) 2026 crDroid Android (blossom)
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
 */

package com.android.systemui.qs.tiles;

import android.app.ActivityTaskManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import javax.inject.Inject;

/**
 * Tile de ajustes rápidos: manda la app que estás usando a una VENTANA FLOTANTE (PiP).
 *
 * Android solo deja entrar en PiP a las apps que lo declaran en su manifiesto Y que además llaman
 * ellas mismas a la API — por eso casi ninguna app lo ofrece. Aquí lo dispara el SISTEMA:
 * {@code enterPictureInPictureModeTop()} busca la actividad de primer plano y la ancla, sin que la
 * app tenga que cooperar. El requisito del manifiesto lo levanta {@code ro.config.force_pip}
 * (ver ActivityRecord.FORCE_PIP).
 *
 * ⚠️ Lo que se ve en la ventanita es la app ENTERA encogida, no el vídeo recortado: PiP ancla la
 * actividad, no un vídeo. Si la app estaba en vídeo a pantalla completa el resultado es el
 * esperado; si no, verás su interfaz en miniatura.
 */
public class PipTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "pip_any";

    private static final String TAG = "PipTile";

    private final PanelInteractor mPanelInteractor;

    @Inject
    public PipTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            PanelInteractor panelInteractor
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mPanelInteractor = panelInteractor;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.CRDROID_SETTINGS;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {}

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        // Primero se cierra el panel: mientras esté abierto, la "app de primer plano" seguiría
        // siendo la sombra de notificaciones y no habría nada que anclar.
        mPanelInteractor.collapsePanels();
        mHandler.postDelayed(() -> {
            try {
                if (!ActivityTaskManager.getService().enterPictureInPictureModeTop()) {
                    Log.d(TAG, "no se pudo anclar la app de primer plano");
                }
            } catch (RemoteException e) {
                Log.w(TAG, "enterPictureInPictureModeTop", e);
            }
        }, 250);
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.pip_tile_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.pip_tile_label);
        state.contentDescription = state.label;
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_pip);
        state.value = true;
        state.state = Tile.STATE_INACTIVE;
    }
}
