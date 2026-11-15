package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/**
 * Mantiene una única instancia del servidor HTTP dentro del proceso de la app.
 * El servidor queda desacoplado de CameraService para que un reinicio de la
 * cámara/captura no derribe el servidor.
 */
public final class WebServerManager {
    private static final String TAG = "WebServerManager";
    private static final int PORT = 8080;
    private static WebServer server;

    private WebServerManager() {}

    public static synchronized WebServer start(Context context) throws IOException {
        if (server == null) {
            server = new WebServer(PORT);
            server.start(10000, false);
            Log.i(TAG, "WebServer iniciado en puerto " + getPuertoInterno());
        }
        return server;
    }

    public static synchronized WebServer get() {
        return server;
    }

    public static synchronized WebServer getOrStart(Context context) throws IOException {
        return start(context);
    }

    public static synchronized void attachCameraService(CameraService service) {
        if (server != null) {
            server.setCameraService(service);
        }
    }

    public static synchronized void detachCameraService(CameraService service) {
        if (server != null) {
            server.clearCameraService(service);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.detenerAudio();
                server.stop();
            } catch (Exception e) {
                Log.w(TAG, "Error deteniendo WebServer", e);
            }
            server = null;
        }
    }

    /**
     * Puerto en el que el servidor está escuchando realmente.
     * Si el servidor aún no está creado, devuelve el puerto configurado por defecto.
     */
    public static synchronized int getPuerto() {
        return getPuertoInterno();
    }

    private static int getPuertoInterno() {
        if (server != null) {
            try {
                // Si WebServer expone el puerto real, úsalo.
                int real = server.getListeningPort();
                if (real > 0) return real;
            } catch (Throwable ignored) {
                // Si WebServer no tiene el método, caemos al PORT configurado.
            }
        }
        return PORT;
    }
}
