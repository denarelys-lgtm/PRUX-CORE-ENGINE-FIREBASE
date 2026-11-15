package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CloudflareTunnelManager {

    private static final String TAG = "PruxCloudflare";
    private static Process tunnelProcess = null;

    /** Regex acotada a URLs reales de túneles rápidos de Cloudflare. */
    private static final Pattern URL_TUNEL = Pattern.compile(
            "https://[a-z0-9-]+\\.trycloudflare\\.com\\b");

    public static synchronized void iniciarTunel(Context context) {
        if (tunnelProcess != null) {
            Log.i(TAG, "El túnel ya está en ejecución.");
            return;
        }

        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                // EL TRUCO: El binario ahora se lee desde la carpeta de librerías nativas,
                // donde Android SÍ permite ejecución (esquivando el bloqueo W^X).
                File tunnelBin = new File(appContext.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");

                if (!tunnelBin.exists()) {
                    Log.e(TAG, "El binario libcloudflared.so no existe en la ruta nativa.");
                    return;
                }

                Log.i(TAG, "Ejecutando binario desde: " + tunnelBin.getAbsolutePath());

                String binPath = tunnelBin.getAbsolutePath();
                ProcessBuilder pb = new ProcessBuilder(
                        binPath, "tunnel", "--url", "http://localhost:8080");
                pb.redirectErrorStream(true);

                tunnelProcess = pb.start();
                Log.i(TAG, "🚀 Túnel de Cloudflare iniciado en segundo plano");

                String ultimaReportada = null;

                try (InputStream processInput = tunnelProcess.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(processInput))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, line);
                        // Extracción robusta + dedupe para no re-reportar la misma URL.
                        String url = extraerUrlDeLinea(line);
                        if (url != null && !url.equals(ultimaReportada)) {
                            ultimaReportada = url;
                            Log.i(TAG, "🌐 URL Pública detectada: " + url);
                            DeviceRegistryManager.registrarDispositivoEnNube(appContext, url);
                        }
                    }
                }

                int exitCode = tunnelProcess.waitFor();
                Log.w(TAG, "cloudflared terminó con código " + exitCode);
                tunnelProcess = null;

            } catch (Exception e) {
                Log.e(TAG, "Error en el túnel de Cloudflare", e);
                if (tunnelProcess != null) {
                    try { tunnelProcess.destroyForcibly(); } catch (Exception ignored) {}
                    tunnelProcess = null;
                }
            }
        }, "Prux-Tunnel-Thread").start();
    }

    private static String extraerUrlDeLinea(String linea) {
        if (linea == null) return null;
        Matcher m = URL_TUNEL.matcher(linea);
        return m.find() ? m.group() : null;
    }

    public static synchronized void detenerTunel() {
        if (tunnelProcess == null) return;
        try {
            tunnelProcess.destroy();
            if (!tunnelProcess.waitFor(5, TimeUnit.SECONDS)) {
                tunnelProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tunnelProcess.destroyForcibly();
        } finally {
            tunnelProcess = null;
            Log.i(TAG, "Túnel de Cloudflare detenido.");
        }
    }
}
