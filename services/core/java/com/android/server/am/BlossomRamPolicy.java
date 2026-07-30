/*
 * BlossomRamPolicy — v3 "tres niveles" (30 jul 2026).
 * Añade el lado OPUESTO de la protección: paquetes EFÍMEROS ("no cachear").
 * Filosofía: nosotros fijamos PRIORIDAD, Android sigue decidiendo cuándo y cuánto matar.
 *
 *   Protegida  -> adj 900 (CACHED_APP_MIN_ADJ)  = última cacheada en morir  [ya existía]
 *   Normal     -> sin tocar                     = el planificador decide    [default]
 *   Efímera    -> adj 999 (CACHED_APP_MAX_ADJ)  = primera en morir          [NUEVO]
 *
 * Config: /data/system/ramprotect.conf (system_server corre como uid system y NO puede
 * leer /data/adb, que es 700 root + SELinux adb_data_file). Se prueba una lista de rutas.
 *   com.foo      -> protegida
 *   !com.bar     -> efímera (no cachear)
 *   # comentario
 *
 * Constantes verificadas en el árbol: ProcessList.java:212 CACHED_APP_MAX_ADJ=999,
 * :213 CACHED_APP_MIN_ADJ=900, :218 CACHED_APP_LMK_FIRST_ADJ=950.
 */
package com.android.server.am;

import android.os.SystemProperties;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Única fuente de verdad de la política de RAM del 10A. Sin estado mutable en hot-path. */
final class BlossomRamPolicy {
    static final String TAG = "BlossomRamPolicy";

    static final boolean ENABLED  = SystemProperties.getBoolean("persist.blossom.ramplanner", true);
    static final boolean PROTECT  = ENABLED
            && SystemProperties.getBoolean("persist.blossom.ramplanner.protect", false);
    /** Fase C: lista "no cachear". Flag propio para poder apagarla sin tocar la protección. */
    static final boolean EPHEMERAL_ENABLED = ENABLED
            && SystemProperties.getBoolean("persist.blossom.ramplanner.ephemeral", false);

    // --- Fase A: valores tuneados (equipo 2GB, zram 906MB) ---
    static final int   MAX_CACHED_PROCESSES      = 16;
    static final boolean PROACTIVE_KILLS_ENABLED = true;
    static final float LOW_SWAP_THRESHOLD_PERCENT = 0.20f;
    static final long  MAX_EMPTY_TIME_MILLIS     = 15L * 60 * 1000;

    // --- Fase B/C: slots de la banda cached ---
    static final int PROTECTED_CACHED_ADJ = ProcessList.CACHED_APP_MIN_ADJ; // 900 = la última en morir
    static final int EPHEMERAL_CACHED_ADJ = ProcessList.CACHED_APP_MAX_ADJ; // 999 = la primera en morir

    /** Rutas candidatas, en orden. La 1a es la unica legible por uid system. */
    private static final String[] CONF_PATHS = {
            "/data/system/ramprotect.conf",
            "/data/adb/ramprotect.conf",   // legado: solo legible si el proceso es root
    };

    private static final Set<String> PROTECTED;
    private static final Set<String> EPHEMERAL;

    static {
        Set<String> prot = new HashSet<>(Arrays.asList(
                "com.yellowpepper.pichincha",   // Pichincha
                "com.pacifico.movilmatico",     // Pacífico
                "com.whatsapp"                  // WhatsApp
        ));
        Set<String> eph = new HashSet<>();
        for (String path : CONF_PATHS) {
            boolean leido = false;
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    if (line.charAt(0) == '!') {
                        String p = line.substring(1).trim();
                        if (!p.isEmpty()) eph.add(p);
                    } else {
                        prot.add(line);
                    }
                }
                leido = true;
            } catch (Exception ignored) { /* archivo opcional o sin permiso */ }
            if (leido) {
                Slog.i(TAG, "config leida de " + path);
                break;
            }
        }
        // Una app no puede ser las dos cosas: la protección gana (menos sorpresas).
        eph.removeAll(prot);
        PROTECTED = Collections.unmodifiableSet(prot);
        EPHEMERAL = Collections.unmodifiableSet(eph);
        if (ENABLED) {
            Slog.i(TAG, "protegidos=" + PROTECTED.size() + " efimeros=" + EPHEMERAL.size()
                    + " enabled=" + ENABLED + " protect=" + PROTECT
                    + " ephemeral=" + EPHEMERAL_ENABLED);
        }
    }

    /** O(1). Hot-path applyLruAdjust — mantener trivial. */
    static boolean isProtected(String packageName) {
        return PROTECT && packageName != null && PROTECTED.contains(packageName);
    }

    /** O(1). Hot-path. "No cachear": primera víctima dentro de la banda cached. */
    static boolean isEphemeral(String packageName) {
        return EPHEMERAL_ENABLED && packageName != null && EPHEMERAL.contains(packageName);
    }

    private BlossomRamPolicy() {}
}
