/*
 * BlossomRamPolicy — override centralizado del planificador de RAM (10A 2GB).
 * VERIFICADO contra el árbol real crDroid en WSL el 24 jul 2026:
 *   - paquete com.android.server.am (mismo que ActivityManagerConstants/OomAdjuster) ✓
 *   - ProcessList.CACHED_APP_MIN_ADJ = 900, public static final ✓ (ProcessList.java:213)
 *   - ProcessRecord.info = volatile ApplicationInfo, accesible en el paquete ✓ (ProcessRecord.java:95)
 *   - único applyLruAdjust del árbol = OomAdjuster.java:947 (OomAdjusterImpl NO lo sobreescribe) ✓
 *   - USE_TIERED_CACHED_ADJ existe en las constantes pero NADIE lo consume = código muerto ✓
 * Ubicación: frameworks/base/services/core/java/com/android/server/am/BlossomRamPolicy.java
 * Feature-flags (props persistentes; toggle sin reflashear ante jank —NO ante bootloop—):
 *   persist.blossom.ramplanner        = 1|0  (maestro; 0 => framework 100% stock)
 *   persist.blossom.ramplanner.protect= 1|0  (Fase B: protección per-paquete)
 * Lista protegida editable en runtime: /data/adb/ramprotect.conf (1 paquete por línea).
 */
package com.android.server.am;

import android.os.SystemProperties;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Única fuente de verdad de la política de RAM del 10A. Sin estado mutable en hot-path. */
final class BlossomRamPolicy {
    static final String TAG = "BlossomRamPolicy";

    // --- Feature flags (leídos UNA vez; el hot-path solo lee booleans cacheados) ---
    // ENABLED (Fase A: valores) por defecto ON = riesgo casi nulo (solo cambian números default), permanente.
    static final boolean ENABLED  = SystemProperties.getBoolean("persist.blossom.ramplanner", true);
    // PROTECT (Fase B: clamp per-paquete en el HOT-PATH applyLruAdjust) por defecto OFF a propósito:
    // es la parte con riesgo de bootloop → validar el 1er build con Fase A y activar Fase B tras probar
    //   setprop persist.blossom.ramplanner.protect 1 ; reboot
    static final boolean PROTECT  = ENABLED
            && SystemProperties.getBoolean("persist.blossom.ramplanner.protect", false);

    // --- Fase A: valores tuneados (equipo 2GB, zram 906MB) ---
    // Reemplazan los defaults del framework y TODO el service.d runtime (permanente, sin race de boot).
    // OJO: en ESTE árbol DEFAULT_MAX_CACHED_PROCESSES = 128 (no 1024 como en AOSP puro).
    static final int   MAX_CACHED_PROCESSES      = 16;
    static final boolean PROACTIVE_KILLS_ENABLED = true;        // default del árbol: false
    static final float LOW_SWAP_THRESHOLD_PERCENT = 0.20f;      // default del árbol: 0.10f
    static final long  MAX_EMPTY_TIME_MILLIS     = 15L * 60 * 1000; // default del árbol: 30 min

    // --- Fase B: protección per-paquete dentro de la banda cached ---
    // Clamp al slot cached MÁS protegido (última cacheada en morir) SIN sacarla de la banda.
    static final int PROTECTED_CACHED_ADJ = ProcessList.CACHED_APP_MIN_ADJ; // 900

    // Baked por defecto (banca conserva sesión/estado en 2GB) + editable en /data/adb/ramprotect.conf.
    private static final Set<String> PROTECTED = loadProtected();

    private static Set<String> loadProtected() {
        Set<String> s = new HashSet<>(Arrays.asList(
                "com.yellowpepper.pichincha",   // Pichincha — CONFIRMADO en el equipo (pm list) 24 jul
                "com.pacifico.movilmatico",     // Pacífico — CONFIRMADO
                "com.whatsapp"                  // WhatsApp — CONFIRMADO
        ));
        try (BufferedReader r = new BufferedReader(new FileReader("/data/adb/ramprotect.conf"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.charAt(0) != '#') s.add(line);
            }
        } catch (Exception ignored) { /* archivo opcional */ }
        if (ENABLED) Slog.i(TAG, "protegidos=" + s.size() + " enabled=" + ENABLED + " protect=" + PROTECT);
        return s;
    }

    /** O(1). Llamado en el hot-path applyLruAdjust — mantener trivial. */
    static boolean isProtected(String packageName) {
        return PROTECT && packageName != null && PROTECTED.contains(packageName);
    }

    private BlossomRamPolicy() {}
}
