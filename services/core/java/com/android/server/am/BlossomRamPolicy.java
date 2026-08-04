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

import android.os.SystemClock;
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

    /**
     * Propiedad "timbre": quien cambie la política (el Guardián, un script) escribe el fichero y
     * luego INCREMENTA esta prop. Aquí se relee sola, sin reiniciar.
     *   setprop blossom.ramplanner.seq $(( $(getprop blossom.ramplanner.seq 2>/dev/null || echo 0) + 1 ))
     */
    private static final String PROP_SEQ = "blossom.ramplanner.seq";
    /** Cada cuánto se mira el timbre. El hot-path solo lee un long volátil salvo una vez cada tanto. */
    private static final long CHECK_INTERVAL_MS = 5000;

    private static volatile boolean sProtectOn;
    private static volatile boolean sEphemeralOn;
    private static volatile long sLastCheckMs;
    private static volatile int  sSeq = -1;

    /** Compatibilidad: el resto del código lee PROTECT() en vez del antiguo campo PROTECT. */
    static boolean PROTECT() { return sProtectOn; }

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
            "/system/etc/ramprotect.conf", // default horneado en la ROM
    };

    private static volatile Set<String> PROTECTED = Collections.emptySet();
    private static volatile Set<String> EPHEMERAL = Collections.emptySet();

    static {
        cargar();
    }

    /**
     * Relee la política. Se llama al arrancar y cada vez que cambia el "timbre".
     * Barato y poco frecuente: el fichero son unas decenas de líneas.
     */
    private static void cargar() {
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
        // Las banderas también se releen: así el interruptor del Guardián surte efecto sin reiniciar.
        sProtectOn = ENABLED
                && SystemProperties.getBoolean("persist.blossom.ramplanner.protect", true);
        sEphemeralOn = ENABLED
                && SystemProperties.getBoolean("persist.blossom.ramplanner.ephemeral", false);
        if (ENABLED) {
            Slog.i(TAG, "protegidos=" + PROTECTED.size() + " efimeros=" + EPHEMERAL.size()
                    + " enabled=" + ENABLED + " protect=" + sProtectOn
                    + " ephemeral=" + sEphemeralOn);
        }
    }

    /**
     * Mira el timbre como mucho una vez cada CHECK_INTERVAL_MS. En el caso normal esto es
     * leer un long volátil y comparar: nada de IO ni de lecturas de propiedades en el hot-path.
     */
    private static void quizaRecargar() {
        final long now = SystemClock.uptimeMillis();
        if (now - sLastCheckMs < CHECK_INTERVAL_MS) return;
        sLastCheckMs = now;
        final int seq = SystemProperties.getInt(PROP_SEQ, 0);
        if (seq != sSeq) {
            sSeq = seq;
            cargar();
        }
    }

    /** O(1). Hot-path applyLruAdjust — mantener trivial. */
    static boolean isProtected(String packageName) {
        quizaRecargar();
        return sProtectOn && packageName != null && PROTECTED.contains(packageName);
    }

    /** O(1). Hot-path. "No cachear": primera víctima dentro de la banda cached. */
    static boolean isEphemeral(String packageName) {
        quizaRecargar();
        return sEphemeralOn && packageName != null && EPHEMERAL.contains(packageName);
    }

    private BlossomRamPolicy() {}
}
