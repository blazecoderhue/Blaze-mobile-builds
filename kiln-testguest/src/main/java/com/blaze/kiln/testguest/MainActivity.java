package com.blaze.kiln.testguest;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Kiln M1 test guest (Blazeinchue). Exercises exactly what M1 must support: its own layout, theme and strings
 * (with a landscape variant), buttons, a text field, saved instance state across rotation, internal files and
 * SharedPreferences. Every start writes {@link #START_FILE} so the Kiln test harness can see the guest really ran
 * and where its files went.
 */
public class MainActivity extends Activity {
    static final String START_FILE = "kiln-guest-start.txt";
    static final String NOTE_FILE = "kiln-guest-note.txt";
    static final String PREFS = "guest";
    private static final String K_TAPS = "taps";

    private int taps;
    private TextView tapsView;
    private TextView savedView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState != null) taps = savedInstanceState.getInt(K_TAPS, 0);
        tapsView = findViewById(R.id.taps);
        savedView = findViewById(R.id.saved);
        final EditText note = findViewById(R.id.note);
        Button tap = findViewById(R.id.tap);
        Button save = findViewById(R.id.save);
        Button close = findViewById(R.id.close);

        tap.setOnClickListener(v -> { taps++; showTaps(); });
        save.setOnClickListener(v -> save(note.getText().toString()));
        close.setOnClickListener(v -> finish());

        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        String info = "package " + getPackageName() + "\n"
                + "filesDir " + getFilesDir().getAbsolutePath() + "\n"
                + "pid " + Process.myPid() + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "recreated " + (savedInstanceState != null);
        ((TextView) findViewById(R.id.info)).setText(info);
        showTaps();
        showSaved();

        String start = "created=" + System.currentTimeMillis() + "\n"
                + "filesDir=" + getFilesDir().getAbsolutePath() + "\n"
                + "package=" + getPackageName() + "\n"
                + "pid=" + Process.myPid() + "\n"
                + "recreated=" + (savedInstanceState != null) + "\n"
                + "orientation=" + (land ? "landscape" : "portrait") + "\n"
                + "taps=" + taps + "\n";
        try {
            write(new File(getFilesDir(), START_FILE), start);
        } catch (IOException e) {
            savedView.setText(getString(R.string.save_failed, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(K_TAPS, taps);
    }

    private void showTaps() {
        tapsView.setText(getString(R.string.taps, taps));
    }

    private void showSaved() {
        File f = new File(getFilesDir(), NOTE_FILE);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        int count = p.getInt("saves", 0);
        if (!f.isFile()) { savedView.setText(R.string.nothing_saved); return; }
        try {
            savedView.setText(getString(R.string.saved, read(f), count));
        } catch (IOException e) {
            savedView.setText(getString(R.string.save_failed, e.getMessage()));
        }
    }

    private void save(String text) {
        try {
            write(new File(getFilesDir(), NOTE_FILE), text);
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            // commit(), not apply(): the tester may kill the process right after tapping Save.
            p.edit().putInt("saves", p.getInt("saves", 0) + 1).putString("last", text).commit();
            showSaved();
        } catch (IOException e) {
            savedView.setText(getString(R.string.save_failed, e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private static String read(File f) throws IOException {
        byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }
}
