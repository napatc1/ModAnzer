package com.napatc1.modanzer;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ModAnzer for Android. Pick a mods folder (or a launcher / .minecraft folder) or some
 * .jar files; each jar is hashed, looked up on Modrinth and Megabase, and unknown jars
 * are searched (including nested jars) for well-known cheat module names.
 * Read-only: only SHA1 hashes are sent over the network.
 */
public class MainActivity extends Activity {

    private static final int REQ_TREE = 1;
    private static final int REQ_FILES = 2;

    // Matched case-sensitively so ordinary words like "velocity" don't trigger.
    private static final String[] CHEATS = {
            "AimAssist", "AnchorTweaks", "AutoAnchor", "AutoCrystal", "AutoDoubleHand",
            "AutoHitCrystal", "AutoPot", "AutoTotem", "AutoArmor", "InventoryTotem",
            "Hitboxes", "JumpReset", "LegitTotem", "PingSpoof", "SelfDestruct",
            "ShieldBreaker", "TriggerBot", "Velocity", "AxeSpam", "WebMacro", "FastPlace",
            "KillAura", "CrystalAura", "AnchorAura", "AutoClicker"
    };
    private static final Pattern CHEAT_RE;
    static {
        StringBuilder b = new StringBuilder();
        for (String c : CHEATS) {
            if (b.length() > 0) b.append('|');
            b.append(Pattern.quote(c));
        }
        CHEAT_RE = Pattern.compile(b.toString());
    }

    private static final Set<String> MOD_DIRS = new HashSet<>(Arrays.asList("mods", "user-mods"));
    private static final Set<String> SKIP_DIRS = new HashSet<>(Arrays.asList(
            ".git", ".gradle", ".cache", "assets", "libraries", "versions", "logs",
            "crash-reports", "screenshots", "saves", "resourcepacks", "shaderpacks",
            "natives", "jre", "runtime", "java", "cache", "temp", "tmp"));
    private static final int MAX_DEPTH = 7;
    private static final int MAX_ENTRY = 8 * 1024 * 1024;
    private static final String UA = "ModAnzer-Android (github.com/napatc1/ModAnzer)";

    private static final int BG = 0xFF121212, CARD = 0xFF1E1E1E;
    private static final int C_TEXT = 0xFFE6E6E6, C_DIM = 0xFF8A8A8A, C_HEAD = 0xFF4FC3F7;
    private static final int C_OK = 0xFF66BB6A, C_UNK = 0xFFFFB74D, C_BAD = 0xFFEF5350, C_HIT = 0xFFCE93D8;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView status, output;
    private Button bFolder, bFiles;
    private boolean busy;

    private static class Jar {
        String folder, name, hash;
        Uri uri;
        Jar(String folder, String name, Uri uri) { this.folder = folder; this.name = name; this.uri = uri; }
    }

    private static class Child {
        String id, name;
        boolean dir;
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draw behind the system bars on every Android version (Android 15+ always does)
        // and pad the content by the bar sizes, so the layout is the same everywhere.
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("ModAnzer");
        title.setTextColor(0xFFFFD54F);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Minecraft mod analyzer • based on HabibiModAnalyzer by HadronCollision");
        sub.setTextColor(C_DIM);
        sub.setTextSize(12);
        sub.setPadding(0, 0, 0, dp(14));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        bFolder = makeButton("Pick folder", 0xFFFFD54F);
        bFiles = makeButton("Pick .jar files", 0xFF4FC3F7);
        row.addView(bFolder);
        row.addView(bFiles);
        root.addView(row);

        TextView tip = new TextView(this);
        tip.setText("Pick your mods folder, or a whole launcher / .minecraft folder and every mods folder inside it is scanned.\n"
                + "PojavLauncher and similar launchers: open the picker's side menu (☰) and choose the launcher there.");
        tip.setTextColor(C_DIM);
        tip.setTextSize(12);
        tip.setPadding(dp(2), dp(12), dp(2), dp(8));
        root.addView(tip);

        status = new TextView(this);
        status.setTextColor(0xFFFFD54F);
        status.setTextSize(13);
        status.setPadding(dp(2), 0, dp(2), dp(8));
        status.setVisibility(View.GONE);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable card = new GradientDrawable();
        card.setColor(CARD);
        card.setCornerRadius(dp(12));
        scroll.setBackground(card);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        output = new TextView(this);
        output.setTextColor(C_TEXT);
        output.setTextSize(13);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setTextIsSelectable(true);
        output.setGravity(Gravity.TOP);
        output.setText(intro());
        scroll.addView(output);
        root.addView(scroll);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();

        bFolder.setOnClickListener(v -> {
            if (busy) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_TREE);
        });
        bFiles.setOnClickListener(v -> {
            if (busy) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_FILES);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_TREE && data.getData() != null) {
            final Uri tree = data.getData();
            start(() -> collectFromTree(tree));
        } else if (requestCode == REQ_FILES) {
            final List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            start(() -> {
                List<Jar> jars = new ArrayList<>();
                for (Uri u : uris) {
                    String n = displayName(u);
                    if (n != null && n.toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(new Jar("Selected files", n, u));
                }
                return jars;
            });
        }
    }

    private interface Collector { List<Jar> collect() throws Exception; }

    private void start(final Collector collector) {
        busy = true;
        bFolder.setEnabled(false);
        bFiles.setEnabled(false);
        bFolder.setAlpha(0.5f);
        bFiles.setAlpha(0.5f);
        output.setText("");
        setStatus("Looking for mods...");
        exec.execute(() -> {
            CharSequence result;
            try {
                result = analyze(collector.collect());
            } catch (Throwable t) {
                SpannableStringBuilder sb = new SpannableStringBuilder();
                append(sb, "Something went wrong:\n" + t, C_BAD, false);
                result = sb;
            }
            final CharSequence fin = result;
            ui.post(() -> {
                output.setText(fin);
                status.setVisibility(View.GONE);
                busy = false;
                bFolder.setEnabled(true);
                bFiles.setEnabled(true);
                bFolder.setAlpha(1f);
                bFiles.setAlpha(1f);
            });
        });
    }

    private void setStatus(final String s) {
        ui.post(() -> {
            status.setText(s);
            status.setVisibility(View.VISIBLE);
        });
    }

    private static void append(SpannableStringBuilder sb, String text, int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // What the app does and doesn't do, shown before the first scan.
    private static CharSequence intro() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "This is NOT a hacking or malware tool. It is read-only and only:\n", C_TEXT, true);
        append(sb, " - Reads the mods folder or .jar files you pick\n"
                + " - Makes a fingerprint (SHA1 hash) of each mod\n"
                + " - Looks those fingerprints up on Modrinth and Megabase to name your mods\n"
                + " - Searches mods it doesn't recognise for known cheat names\n"
                + " - Shows the results on this screen\n\n", C_DIM, false);
        append(sb, "It does NOT:\n", C_TEXT, true);
        append(sb, " - Upload your files, or send anything except the mod fingerprints\n"
                + " - Look at anything you didn't pick\n"
                + " - Read passwords, accounts, contacts, photos or messages\n"
                + " - Change, move or delete any files\n"
                + " - Ask for any permission except Internet\n\n", C_DIM, false);
        append(sb, "Source code: github.com/napatc1/ModAnzer\n\n", C_DIM, false);
        append(sb, "Tap Pick folder or Pick .jar files to start.", 0xFFFFD54F, true);
        return sb;
    }

    // ------------------------------------------------------------------ finding jars

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private List<Child> children(Uri tree, String docId) {
        List<Child> out = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor c = getContentResolver().query(uri, cols, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Child ch = new Child();
                ch.id = c.getString(0);
                ch.name = c.getString(1);
                ch.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                if (ch.id != null && ch.name != null) out.add(ch);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private List<Jar> collectFromTree(Uri tree) {
        List<Jar> jars = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        String rootName = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId));
        if (rootName == null) rootName = "picked folder";

        if (MOD_DIRS.contains(rootName.toLowerCase(Locale.ROOT))) {
            walkMods(tree, rootId, rootName, 0, jars);
        } else {
            walk(tree, rootId, rootName, 0, jars);
        }
        return jars;
    }

    // Look for mods folders; jars sitting directly in the picked folder count too.
    private void walk(Uri tree, String docId, String path, int depth, List<Jar> jars) {
        for (Child c : children(tree, docId)) {
            String lower = c.name.toLowerCase(Locale.ROOT);
            if (c.dir) {
                if (MOD_DIRS.contains(lower)) {
                    setStatus("Found " + path + "/" + c.name);
                    walkMods(tree, c.id, path + "/" + c.name, 0, jars);
                } else if (depth < MAX_DEPTH && !SKIP_DIRS.contains(lower)) {
                    walk(tree, c.id, path + "/" + c.name, depth + 1, jars);
                }
            } else if (depth == 0 && lower.endsWith(".jar")) {
                jars.add(new Jar(path, c.name, DocumentsContract.buildDocumentUriUsingTree(tree, c.id)));
            }
        }
    }

    // Inside a mods folder: take the jars, plus one level of subfolders (mods/1.21/...).
    private void walkMods(Uri tree, String docId, String path, int level, List<Jar> jars) {
        for (Child c : children(tree, docId)) {
            if (c.dir) {
                if (level < 1) walkMods(tree, c.id, path + "/" + c.name, level + 1, jars);
            } else if (c.name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                jars.add(new Jar(path, c.name, DocumentsContract.buildDocumentUriUsingTree(tree, c.id)));
            }
        }
    }

    // ------------------------------------------------------------------ analysis

    private CharSequence analyze(List<Jar> jars) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (jars.isEmpty()) {
            append(sb, "No .jar mods found here.\n\n", C_BAD, true);
            append(sb, "Pick the mods folder itself, or the launcher / .minecraft folder that contains it.", C_DIM, false);
            return sb;
        }

        // 1. hash
        for (int i = 0; i < jars.size(); i++) {
            setStatus("Hashing " + (i + 1) + " / " + jars.size());
            jars.get(i).hash = sha1(jars.get(i).uri);
        }
        List<String> hashes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Jar j : jars) if (j.hash != null && seen.add(j.hash)) hashes.add(j.hash);

        // 2. look up
        Map<String, String> known = new HashMap<>();
        String netError = null;
        try {
            setStatus("Checking " + hashes.size() + " mods on Modrinth...");
            modrinth(hashes, known);
        } catch (Exception e) {
            netError = "Modrinth lookup failed: " + e.getMessage();
        }
        List<String> left = new ArrayList<>();
        for (String h : hashes) if (!known.containsKey(h)) left.add(h);
        for (int i = 0; i < left.size(); i++) {
            setStatus("Checking Megabase " + (i + 1) + " / " + left.size());
            String name = megabase(left.get(i));
            if (name != null) known.put(left.get(i), name);
        }

        // 3. scan unknown
        Map<String, List<String[]>> hits = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String h : hashes) if (!known.containsKey(h)) unknown.add(h);
        for (int i = 0; i < unknown.size(); i++) {
            setStatus("Scanning unknown mods " + (i + 1) + " / " + unknown.size());
            String h = unknown.get(i);
            Jar jar = null;
            for (Jar j : jars) if (h.equals(j.hash)) { jar = j; break; }
            List<String[]> found = new ArrayList<>();
            try (InputStream in = getContentResolver().openInputStream(jar.uri)) {
                if (in != null) scanJar(new ZipInputStream(in), jar.name, found, 0);
            } catch (Exception e) {
                found.add(new String[]{jar.name, "could not open: " + e.getMessage()});
            }
            hits.put(h, found);
        }

        // 4. report
        if (netError != null) append(sb, "[!] " + netError + "\n\n", C_BAD, false);

        Map<String, List<Jar>> byFolder = new LinkedHashMap<>();
        for (Jar j : jars) {
            List<Jar> l = byFolder.get(j.folder);
            if (l == null) { l = new ArrayList<>(); byFolder.put(j.folder, l); }
            l.add(j);
        }

        int ok = 0, unk = 0, bad = 0;
        for (Map.Entry<String, List<Jar>> e : byFolder.entrySet()) {
            append(sb, e.getKey() + "  (" + e.getValue().size() + ")\n", C_HEAD, true);
            List<Jar> list = e.getValue();
            java.util.Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            for (Jar j : list) {
                if (j.hash != null && known.containsKey(j.hash)) {
                    ok++;
                    append(sb, " OK  ", C_OK, true);
                    append(sb, known.get(j.hash), C_TEXT, false);
                    append(sb, "\n     " + j.name + "\n", C_DIM, false);
                    continue;
                }
                List<String[]> h = j.hash == null ? null : hits.get(j.hash);
                if (h != null && !h.isEmpty()) {
                    bad++;
                    append(sb, " !!  " + j.name + "\n", C_BAD, true);
                    for (String[] hit : h) {
                        append(sb, "     " + hit[0] + ": ", C_DIM, false);
                        append(sb, hit[1] + "\n", C_HIT, false);
                    }
                } else {
                    unk++;
                    append(sb, " ??  " + j.name + "\n", C_UNK, false);
                }
            }
            sb.append("\n");
        }

        append(sb, "Summary\n", C_HEAD, true);
        append(sb, "  Verified: " + ok + "\n", C_OK, false);
        append(sb, "  Unknown:  " + unk + "\n", C_UNK, false);
        append(sb, "  Cheat:    " + bad + "\n\n", bad > 0 ? C_BAD : C_DIM, bad > 0);
        append(sb, "?? means it's not on Modrinth/Megabase, not that it's a cheat. "
                + "!! is a name match - check it by hand.", C_DIM, false);
        return sb;
    }

    private String sha1(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void addMatches(String text, Set<String> into) {
        Matcher m = CHEAT_RE.matcher(text);
        while (m.find()) into.add(m.group());
    }

    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = zis.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_ENTRY) return null;
        }
        return bos.toByteArray();
    }

    // Entry names, then file contents, then nested jars (up to 3 levels deep).
    private static void scanJar(ZipInputStream zis, String label, List<String[]> hits, int level) throws IOException {
        Set<String> found = new TreeSet<>();
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            String name = e.getName();
            addMatches(name, found);
            if (e.isDirectory()) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jar") && level < 3) {
                byte[] b = readEntry(zis);
                if (b != null) {
                    String inner = name.substring(name.lastIndexOf('/') + 1);
                    try {
                        scanJar(new ZipInputStream(new ByteArrayInputStream(b)), label + " -> " + inner, hits, level + 1);
                    } catch (IOException ignored) { }
                }
            } else if (lower.endsWith(".class") || lower.endsWith(".json") || lower.endsWith(".toml") || lower.endsWith(".mf")) {
                byte[] b = readEntry(zis);
                if (b != null) addMatches(new String(b, StandardCharsets.ISO_8859_1), found);
            }
        }
        if (!found.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (String f : found) {
                if (s.length() > 0) s.append(", ");
                s.append(f);
            }
            hits.add(new String[]{label, s.toString()});
        }
    }

    // ------------------------------------------------------------------ network

    private static String http(String url, String postJson) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            if (postJson != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(postJson.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream is = in) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                }
            }
            String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static void modrinth(List<String> hashes, Map<String, String> known) throws Exception {
        Map<String, String> projectByHash = new HashMap<>();
        for (int i = 0; i < hashes.size(); i += 500) {
            JSONObject body = new JSONObject();
            body.put("hashes", new JSONArray(hashes.subList(i, Math.min(i + 500, hashes.size()))));
            body.put("algorithm", "sha1");
            JSONObject resp = new JSONObject(http("https://api.modrinth.com/v2/version_files", body.toString()));
            JSONArray keys = resp.names();
            if (keys == null) continue;
            for (int k = 0; k < keys.length(); k++) {
                String h = keys.getString(k);
                String pid = resp.getJSONObject(h).optString("project_id", "");
                if (!pid.isEmpty()) projectByHash.put(h.toLowerCase(Locale.ROOT), pid);
            }
        }

        List<String> ids = new ArrayList<>(new TreeSet<>(projectByHash.values()));
        Map<String, String> titleById = new HashMap<>();
        for (int i = 0; i < ids.size(); i += 100) {
            JSONArray chunk = new JSONArray(ids.subList(i, Math.min(i + 100, ids.size())));
            String url = "https://api.modrinth.com/v2/projects?ids=" + URLEncoder.encode(chunk.toString(), "UTF-8");
            JSONArray arr = new JSONArray(http(url, null));
            for (int k = 0; k < arr.length(); k++) {
                JSONObject p = arr.getJSONObject(k);
                titleById.put(p.optString("id"), p.optString("title"));
            }
        }

        for (Map.Entry<String, String> e : projectByHash.entrySet()) {
            String t = titleById.get(e.getValue());
            known.put(e.getKey(), t == null || t.isEmpty() ? "(Modrinth project)" : t);
        }
    }

    private static String megabase(String hash) {
        try {
            JSONObject r = new JSONObject(http("https://megabase.vercel.app/api/query?hash=" + hash, null));
            if (r.has("error") && !r.isNull("error") && r.optBoolean("error", true)) return null;
            JSONObject d = r.optJSONObject("data");
            if (d == null) return null;
            String n = d.optString("name", "");
            return n.isEmpty() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
