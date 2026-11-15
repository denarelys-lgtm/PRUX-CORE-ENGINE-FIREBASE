package com.example.detectcamera;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registra el dispositivo en Realtime Database usando autenticación anónima.
 * Incluye reintentos con backoff y limpieza automática con onDisconnect().
 */
public final class DeviceRegistryManager {

    private static final String TAG = "PruxRegistry";
    private static final String DATABASE_URL =
            "https://detectcameracentral-default-rtdb.firebaseio.com/";
    private static final String DATABASE_NODE = "dispositivos";
    private static final int MAX_REINTENTOS = 5;

    /** Evita race condition: guarda la última URL reportada para no degradar el estado. */
    private static final AtomicReference<String> ULTIMA_URL = new AtomicReference<>(null);
    private static final Object LOCK_REGISTRO = new Object();

    private DeviceRegistryManager() { }

    public static void registrarDispositivoEnNube(Context context, String urlTunel) {
        if (context == null) {
            Log.e(TAG, "Context inválido; no se puede registrar el dispositivo.");
            return;
        }

        final String urlFinal;
        synchronized (LOCK_REGISTRO) {
            // Si llega null/empty pero ya teníamos URL, la reutilizamos para no pisar el estado.
            if ((urlTunel == null || urlTunel.isEmpty()) && ULTIMA_URL.get() != null) {
                urlFinal = ULTIMA_URL.get();
            } else if (urlTunel != null && !urlTunel.isEmpty()) {
                ULTIMA_URL.set(urlTunel);
                urlFinal = urlTunel;
            } else {
                urlFinal = "";
            }
        }

        ejecutarRegistro(context.getApplicationContext(), urlFinal);
    }

    private static void ejecutarRegistro(Context appContext, String url) {
        final String androidId;
        try {
            androidId = Settings.Secure.getString(
                    appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo obtener ANDROID_ID.", e);
            return;
        }

        if (androidId == null || androidId.trim().isEmpty()) {
            Log.e(TAG, "ANDROID_ID vacío; registro cancelado.");
            return;
        }

        Log.i(TAG, "Preparando registro Firebase. URL: "
                + (url.isEmpty() ? "pendiente" : url));

        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = auth.getCurrentUser();
            if (currentUser != null) {
                escribirRegistro(appContext, currentUser, androidId, url);
                return;
            }

            Log.i(TAG, "No hay sesión Firebase; iniciando autenticación anónima.");
            auth.signInAnonymously()
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        if (user == null) {
                            Log.e(TAG, "Firebase autenticó sin devolver usuario.");
                            programarReintento(appContext, url, 1);
                            return;
                        }
                        Log.i(TAG, "Auth Firebase OK. UID: " + user.getUid());
                        escribirRegistro(appContext, user, androidId, url);
                    })
                    .addOnFailureListener(error -> {
                        Log.e(TAG, "FALLÓ AUTH ANÓNIMA: " + error.getMessage(), error);
                        programarReintento(appContext, url, 1);
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error inicializando Firebase.", e);
            programarReintento(appContext, url, 1);
        }
    }

    private static void escribirRegistro(
            Context appContext, FirebaseUser user, String androidId, String url) {
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(DATABASE_URL);
            DatabaseReference deviceRef = database.getReference(DATABASE_NODE).child(androidId);

            Map<String, Object> data = new HashMap<>();
            data.put("modelo", Build.MODEL == null ? "Android" : Build.MODEL);
            data.put("androidId", androidId);
            data.put("firebaseUid", user.getUid());
            data.put("plataforma", "Android");
            data.put("estado", url.isEmpty()
                    ? "servidor_activo_tunel_pendiente" : "conectado");
            data.put("timestamp", ServerValue.TIMESTAMP);
            if (!url.isEmpty()) {
                data.put("url", url);
            }

            // Punto 5: onDisconnect -> si el cliente cae, el nodo queda 'desconectado'.
            deviceRef.onDisconnect().updateChildren(Map.of(
                    "estado", "desconectado",
                    "timestamp", ServerValue.TIMESTAMP
            ));

            deviceRef.updateChildren(data, (DatabaseError error, DatabaseReference ref) -> {
                if (error != null) {
                    Log.e(TAG, "FALLO AL ESCRIBIR EN FIREBASE. code=" + error.getCode()
                            + " msg=" + error.getMessage()
                            + " detalles=" + error.getDetails());
                    programarReintento(appContext, url, 1);
                    return;
                }
                Log.i(TAG, "REGISTRO CONFIRMADO: dispositivos/" + androidId
                        + " | estado=" + (url.isEmpty() ? "pendiente" : "conectado"));
            });
        } catch (Exception e) {
            Log.e(TAG, "Excepción preparando escritura RTDB.", e);
            programarReintento(appContext, url, 1);
        }
    }

    /** Reintento exponencial: 2s, 4s, 8s, 16s, 32s. */
    private static void programarReintento(Context ctx, String url, int intento) {
        if (intento > MAX_REINTENTOS) {
            Log.e(TAG, "Se agotaron los reintentos de registro Firebase.");
            return;
        }
        long delayMs = (long) Math.pow(2, intento) * 1000L;
        Log.i(TAG, "Reintento de registro en " + delayMs + "ms (intento " + intento + ")");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> registrarDispositivoEnNube(ctx, url),
                delayMs);
    }
}
