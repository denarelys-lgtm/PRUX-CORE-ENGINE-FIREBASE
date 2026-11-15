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

    /** 
     * Regex acotada a URLs reales de túneles rápidos.
     * El (?!api\\.) evita que capture la URL de la API si cloudflared imprime un error.
     */
    private static final Pattern URL_TUNEL = Pattern.compile(
            "https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com\\b");

    public static synchronized void iniciarTunel(Context context) {
        if (tunnelProcess != null) {
            Log.i(TAG, "El túnel ya está en ejecución.");
            return;
        }

        final Context appContext = context.getApplicationContext();

        new Thread(() -> {
            try {
                // Leemos el binario desde la carpeta nativa de Android
                File tunnelBin = new File(appContext.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");

                if (!tunnelBin.exists()) {
                    Log.e(TAG, "ERROR FATAL: El binario libcloudflared.so no existe en la ruta nativa.");
                    return;
                }

                Log.i(TAG, "Ejecutando binario desde: " + tunnelBin.getAbsolutePath());

                // Ejecutamos el túnel apuntando al puerto 8080
                ProcessBuilder pb = new ProcessBuilder(
                        tunnelBin.getAbsolutePath(), "tunnel", "--url", "http://localhost:8080");
                pb.redirectErrorStream(true);

                // SOLUCIÓN AL ERROR DE RED: Forzamos a Cloudflare a usar el DNS de Google (8.8.8.8)
                // Esto evita el "connection refused" en el puerto 53 de Android.
                pb.environment().put("TUNNEL_DNS", "8.8.8.8:53");

                tunnelProcess = pb.start();
                Log.i(TAG, "🚀 Túnel de Cloudflare iniciado en segundo plano");

                String ultimaReportada = null;

                try (InputStream processInput = tunnelProcess.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(processInput))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, line);
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
                Log.e(TAG, "Error crítico en el túnel de Cloudflare", e);
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
