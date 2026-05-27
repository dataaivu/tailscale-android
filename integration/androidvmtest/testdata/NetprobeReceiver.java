// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// NetprobeReceiver is embedded by integration/androidvmtest/android_test.go and compiled into a
// temporary helper APK during the emulator integration test.
//
// The receiver runs that APK's packaged Go netprobe binary from a real Android app process, then
// writes the probe output to the app's private files directory for the Go test to read with adb.
package com.tailscale.ipn.integrationprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class NetprobeReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(final Context context, final Intent intent) {
    final PendingResult pending = goAsync();
    String path = context.getApplicationInfo().nativeLibraryDir + "/libnetprobe.so";
    String url = intent.getStringExtra("url");
    new Thread(new ProbeRunnable(context.getApplicationContext(), pending, path, url)).start();
  }

  private static final class ProbeRunnable implements Runnable {
    private final Context context;
    private final PendingResult pending;
    private final String path;
    private final String url;

    ProbeRunnable(Context context, PendingResult pending, String path, String url) {
      this.context = context;
      this.pending = pending;
      this.path = path;
      this.url = url;
    }

    @Override
    public void run() {
      String result;
      try {
        Process proc = new ProcessBuilder(path, url).redirectErrorStream(true).start();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        InputStream in = proc.getInputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
          buf.write(tmp, 0, n);
        }
        if (!proc.waitFor(45, TimeUnit.SECONDS)) {
          proc.destroyForcibly();
          result = "timeout\n" + buf.toString("UTF-8");
        } else {
          result = "exit=" + proc.exitValue() + "\n" + buf.toString("UTF-8");
        }
      } catch (Exception e) {
        result = "error=" + e + "\n";
      }
      try {
        File out = new File(context.getFilesDir(), "result");
        FileOutputStream fos = new FileOutputStream(out);
        fos.write(result.getBytes(StandardCharsets.UTF_8));
        fos.close();
      } catch (Exception ignored) {
      }
      pending.finish();
    }
  }
}
