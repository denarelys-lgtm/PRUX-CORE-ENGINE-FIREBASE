package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
                File binDir = appContext.getFilesDir();
                File tunnelBin = new File(binDir, "cloudflared");

                // Punto 4: si no existe, está corrupto o el asset cambió, se (re)extrae.
                if (!binarioValido(tunnelBin, appContext)) {
                    Log.i(TAG, "Extrayendo/reemplazando cloudflared desde assets (ZIP)...");
                    extraerBinario(appContext, tunnelBin);
                }

                if (!tunnelBin.canExecute() && !tunnelBin.setExecutable(true)) {
                    Log.e(TAG, "El binario no es ejecutable: " + tunnelBin.getAbsolutePath());
                    return;
                }

                String binPath = tunnelBin.getAbsolutePath();
                ProcessBuilder pb = new ProcessBuilder(
                        binPath, "tunnel", "--url", "http://localhost:8080");
                pb.redirectErrorStream(true);

                tunnelProcess = pb.start();
                Log.i(TAG, "🚀 Túnel de Cloudflare iniciado en segundo plano");

                String ultimaReportada = null;

                try (InputStream processInput = tunnelProcess.getInputStream();
                     java.io.BufferedReader reader =
                             new java.io.BufferedReader(new java.io.InputStreamReader(processInput))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, line);
                        // Punto 9: extracción robusta + dedupe para no re-reportar la misma URL.
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

    /**
     * Comprueba que el binario exista, tenga tamaño coherente con el asset,
     * y sea ejecutable. Si algo falla, se fuerza re-extracción.
     */
    private static boolean binarioValido(File tunnelBin, Context context) {
        if (!tunnelBin.exists() || tunnelBin.length() == 0) return false;

        long tamanoEsperado = obtenerTamanoAssetSinComprimir(context, "cloudflared.zip");
        if (tamanoEsperado > 0 && tunnelBin.length() != tamanoEsperado) {
            Log.w(TAG, "Tamaño del binario no coincide (" + tunnelBin.length()
                    + " vs " + tamanoEsperado + "). Se re-extraerá.");
            return false;
        }
        return tunnelBin.canExecute() || tunnelBin.setExecutable(true);
    }

    /** Lee el tamaño del entry 'cloudflared' dentro del ZIP de assets sin extraerlo. */
    private static long obtenerTamanoAssetSinComprimir(Context context, String assetZip) {
        try (InputStream is = context.getAssets().open(assetZip);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (esEntryCloudflared(e.getName())) {
                    return e.getSize();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo leer el tamaño del asset", e);
        }
        return -1;
    }

    /** Extrae el binario a un archivo temporal y lo mueve atómicamente a destino. */
    private static void extraerBinario(Context context, File destino) throws Exception {
        File tmp = new File(destino.getParentFile(), "cloudflared.tmp");
        boolean encontrado = false;

        try (InputStream assetStream = context.getAssets().open("cloudflared.zip");
             ZipInputStream zis = new ZipInputStream(assetStream)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (esEntryCloudflared(entry.getName())) {
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[8 * 1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                        fos.getFD().sync();
                    }
                    encontrado = true;
                    break;
                }
                zis.closeEntry();
            }
        }

        if (!encontrado) {
            throw new IllegalStateException("cloudflared no encontrado dentro del ZIP");
        }

        if (destino.exists() && !destino.delete()) {
            Log.w(TAG, "No se pudo borrar el binario anterior, se intentará sobrescribir");
        }
        if (!tmp.renameTo(destino)) {
            throw new IllegalStateException("No se pudo mover cloudflared.tmp a cloudflared");
        }
        if (!destino.setExecutable(true)) {
            Log.w(TAG, "setExecutable devolvió false en " + destino.getAbsolutePath());
        }
    }

    private static boolean esEntryCloudflared(String nombre) {
        return "cloudflared".equals(nombre) || nombre.endsWith("/cloudflared");
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
