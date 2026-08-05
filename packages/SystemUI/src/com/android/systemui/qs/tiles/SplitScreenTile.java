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

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

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
import com.android.systemui.statusbar.CommandQueue;
import com.android.app.displaylib.PerDisplayRepository;
import com.android.systemui.model.SysUiState;
import com.android.wm.shell.shared.desktopmode.DesktopState;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE;

import javax.inject.Inject;

/** Quick settings tile: dividir pantalla (split-screen).
 *  Usa CommandQueue.moveFocusedTaskToStageSplit(), que es la via VIVA en Android 16:
 *  la implementa LauncherProxyService y acaba en Launcher -> Shell, metiendo la app en
 *  primer plano dentro del split.
 *
 *  OJO: NO vale CommandQueue.toggleSplitScreen(). Ese callback existe pero su
 *  implementacion por defecto esta VACIA y nadie la implementa en este arbol (era el camino
 *  del Recents legacy, muerto desde que el split se mudo a WMShell): el tile respondia,
 *  el panel se cerraba y no pasaba absolutamente nada. */
public class SplitScreenTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "split_screen";

    private final PanelInteractor mPanelInteractor;
    private final CommandQueue mCommandQueue;
    private final PerDisplayRepository<SysUiState> mSysUiStates;
    private final DesktopState mDesktopState;

    @Inject
    public SplitScreenTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            PanelInteractor panelInteractor,
            CommandQueue commandQueue,
            PerDisplayRepository<SysUiState> sysUiStates,
            DesktopState desktopState
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mPanelInteractor = panelInteractor;
        mCommandQueue = commandQueue;
        mSysUiStates = sysUiStates;
        mDesktopState = desktopState;
    }

    /**
     * ¿Se puede dividir la pantalla AHORA MISMO?
     *
     * Mismo criterio que LauncherProxyService.moveFocusedTaskToStageSplit(): en modo escritorio con
     * freeform activo esa llamada se sale sin hacer nada, así que el tile se pinta apagado en vez de
     * fingir que funciona.
     */
    private boolean puedeDividir() {
        try {
            if (!mDesktopState.canEnterDesktopMode()) return true;
            SysUiState st = mSysUiStates.get(mContext.getDisplayId());
            if (st == null) return true;
            return (st.getFlags() & SYSUI_STATE_FREEFORM_ACTIVE_IN_DESKTOP_MODE) == 0;
        } catch (Throwable t) {
            return true;   // ante la duda, disponible: peor es un tile muerto sin explicación
        }
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
    public void handleSetListening(boolean listening) {
        if (listening) refreshState();   // recalcula disponibilidad al abrir el panel
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        if (!puedeDividir()) return;
        mPanelInteractor.collapsePanels();
        // leftOrTop=true -> la app actual se queda arriba y el selector pide la segunda
        mHandler.post(() -> mCommandQueue.moveFocusedTaskToStageSplit(
                mContext.getDisplayId(), true));
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.split_screen_tile_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = mContext.getString(R.string.split_screen_tile_label);
        state.contentDescription = state.label;
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_split_screen);
        boolean disponible = puedeDividir();
        state.value = disponible;
        state.state = disponible ? Tile.STATE_INACTIVE : Tile.STATE_UNAVAILABLE;
        if (!disponible) {
            state.secondaryLabel = mContext.getString(R.string.split_screen_tile_unavailable);
        } else {
            state.secondaryLabel = null;
        }
    }
}
