import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * aa-tweak: turn on Android Auto's hidden Material 3 Expressive home screen ("Cielo") and its
 * home-screen widget system ("Earth") by overriding Android Auto's server flags in Google Play Services.
 *
 * Run as root:
 *   CLASSPATH=/data/local/tmp/aa-tweak.dex app_process /system/bin AaTweak status
 *   CLASSPATH=/data/local/tmp/aa-tweak.dex app_process /system/bin AaTweak enable [layout|widgets|all]
 *   CLASSPATH=/data/local/tmp/aa-tweak.dex app_process /system/bin AaTweak disable
 *
 * What it writes:
 *  1. Google Play Services' flag override table (phenotype.db -> flag_overrides), linked to Android Auto's
 *     committed config, so the values survive Google's config syncs.
 *  2. The config snapshot Play Services shares with Android Auto (app_phenotype_shared/...), so the change
 *     applies right away. The original values are saved to /data/local/tmp/aa-tweak/ for "disable".
 */
public class AaTweak {
    static final String AA = "com.google.android.projection.gearhead";
    static final String DB = "/data/data/com.google.android.gms/databases/phenotype.db";
    static final String SHARED_ROOT = "/data/data/com.google.android.gms/app_phenotype_shared/";
    static final File STATE_DIR = new File("/data/local/tmp/aa-tweak");
    static final File ORIGINALS = new File(STATE_DIR, "original-values.properties");

    // Value types used by Play Services (flag_overrides.type) and the compact shared snapshot.
    static final int DB_LONG = 1, DB_BOOL = 2, DB_STRING = 4;
    static final int SNAP_FALSE = 0, SNAP_TRUE = 1, SNAP_LONG = 2, SNAP_DOUBLE = 3, SNAP_STRING = 4, SNAP_BYTES = 5;

    static final String P = "CieloFeature__";
    static final Object[][] LAYOUT = {
            {P + "cielo_status", "system_theme"},     // new Material 3 Expressive system UI layouts
            {P + "cielo_focus_enabled", Boolean.TRUE}, // don't require the car to report a touchscreen
    };
    static final Object[][] WIDGETS = {
            {P + "earth_enabled", Boolean.TRUE},       // home-screen widget system (needs Android 17)
            {P + "earth_status", "CAR.SYS"},
    };
    static final Object[][] EXTRAS = {
            {P + "use_draggable_dashboard", Boolean.TRUE},
            {P + "earth_dashboard_widget_combo_enabled", Boolean.TRUE},
            {P + "earth_mini_dashboard_card_enabled", Boolean.TRUE},
    };

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) {
            System.out.println("Run this as root (su).");
            System.exit(1);
        }
        String cmd = args.length > 0 ? args[0] : "status";
        switch (cmd) {
            case "enable": {
                String preset = args.length > 1 ? args[1] : "widgets";
                Map<String, Object> flags = preset(preset);
                if (flags.containsKey(P + "earth_enabled") && Build.VERSION.SDK_INT < 37) {
                    System.out.println("Note: widgets need Android 17 (API 37); this phone is API "
                            + Build.VERSION.SDK_INT + ". Only the new layout will appear.");
                }
                enable(flags);
                status();
                break;
            }
            case "disable":
                disable();
                status();
                break;
            case "status":
                status();
                break;
            default:
                System.out.println("Usage: AaTweak status | enable [layout|widgets|all] | disable");
                System.exit(2);
        }
    }

    static Map<String, Object> preset(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, LAYOUT);
        if (name.equals("widgets") || name.equals("all")) put(m, WIDGETS);
        if (name.equals("all")) put(m, EXTRAS);
        if (!name.matches("layout|widgets|all")) throw new IllegalArgumentException("Unknown preset " + name);
        return m;
    }

    static void put(Map<String, Object> m, Object[][] kv) {
        for (Object[] e : kv) m.put((String) e[0], e[1]);
    }

    static List<String> allNames() {
        List<String> names = new ArrayList<>();
        for (Object[][] group : new Object[][][]{LAYOUT, WIDGETS, EXTRAS}) for (Object[] e : group) names.add((String) e[0]);
        return names;
    }

    // ---------------------------------------------------------------- enable / disable

    static void enable(Map<String, Object> flags) throws Exception {
        SQLiteDatabase db = openDb();
        Gms gms = new Gms(db);
        File shared = gms.sharedSnapshot();
        Map<String, Object[]> snap = shared != null ? readSnapshot(shared) : null;

        if (snap != null && !ORIGINALS.exists()) saveOriginals(snap);

        db.beginTransaction();
        try {
            for (Map.Entry<String, Object> f : flags.entrySet()) gms.upsertOverride(f.getKey(), f.getValue());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
        System.out.println("Saved " + flags.size() + " overrides in Play Services' flag database.");

        sh("am", "force-stop", AA);
        if (snap != null) {
            for (Map.Entry<String, Object> f : flags.entrySet()) snap.put(f.getKey(), toSnap(f.getValue()));
            writeSnapshot(shared, snap);
            System.out.println("Patched the shared config snapshot: " + shared.getName());
        } else {
            System.out.println("No shared config snapshot found; relying on Play Services to push the change.");
        }
        refreshAndroidAuto();
    }

    static void disable() throws Exception {
        SQLiteDatabase db = openDb();
        Gms gms = new Gms(db);
        File shared = gms.sharedSnapshot();
        db.beginTransaction();
        int removed;
        try {
            removed = gms.removeOverrides(allNames());
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
        System.out.println("Removed " + removed + " overrides from Play Services' flag database.");

        sh("am", "force-stop", AA);
        if (shared != null) {
            Map<String, Object[]> snap = readSnapshot(shared);
            Properties orig = new Properties();
            if (ORIGINALS.exists()) try (FileInputStream in = new FileInputStream(ORIGINALS)) { orig.load(in); }
            for (String name : allNames()) {
                String saved = orig.getProperty(name);
                if (saved == null || saved.equals("absent")) snap.remove(name);
                else snap.put(name, decodeSaved(saved));
            }
            writeSnapshot(shared, snap);
            ORIGINALS.delete();
            System.out.println("Restored the original values in " + shared.getName());
        }
        refreshAndroidAuto();
    }

    static void refreshAndroidAuto() throws Exception {
        // Ask Android Auto to pull its config from Play Services again, then restart it.
        sh("am", "broadcast", "-a", "com.google.android.gms.phenotype.UPDATE",
                "--es", "com.google.android.gms.phenotype.PACKAGE_NAME", AA, "-p", AA);
        Thread.sleep(4000);
        sh("am", "force-stop", AA);
        System.out.println("Android Auto restarted. Reconnect to your car (or head unit) to see the change.");
    }

    // ---------------------------------------------------------------- status

    static void status() throws Exception {
        System.out.println();
        System.out.println("Android API " + Build.VERSION.SDK_INT + ", Android Auto " + aaVersion());
        SQLiteDatabase db = openDb();
        Gms gms = new Gms(db);
        File shared = gms.sharedSnapshot();
        Map<String, Object[]> snap = shared != null ? readSnapshot(shared) : new TreeMap<>();
        System.out.printf("%-52s %-14s %s%n", "flag", "override", "Android Auto sees");
        for (String name : allNames()) {
            Object ov = gms.overrideValue(name);
            Object[] s = snap.get(name);
            System.out.printf("%-52s %-14s %s%n", name, ov == null ? "-" : ov, s == null ? "(default)" : s[1]);
        }
        db.close();
    }

    static String aaVersion() throws Exception {
        for (String line : shOut("dumpsys", "package", AA).split("\n")) {
            line = line.trim();
            if (line.startsWith("versionName=")) return line.substring("versionName=".length());
        }
        return "not installed";
    }

    // ---------------------------------------------------------------- Play Services database

    static SQLiteDatabase openDb() {
        if (!new File(DB).exists()) throw new IllegalStateException("Play Services flag database not found: " + DB);
        return SQLiteDatabase.openDatabase(DB, null, SQLiteDatabase.OPEN_READWRITE);
    }

    static class Gms {
        final SQLiteDatabase db;
        final long configPackageId;
        final Long committedState;
        final byte[] secret;
        final boolean hasSource;

        Gms(SQLiteDatabase db) {
            this.db = db;
            try (Cursor c = db.rawQuery("SELECT cp.config_package_id, cp.committed_experiment_state_id, ap.secret "
                    + "FROM config_packages cp LEFT JOIN android_packages ap ON ap.android_package_id = cp.android_package_id "
                    + "WHERE cp.name = ?", new String[]{AA})) {
                if (!c.moveToFirst()) throw new IllegalStateException(
                        "Android Auto isn't registered with Play Services yet. Open Android Auto once, then retry.");
                configPackageId = c.getLong(0);
                committedState = c.isNull(1) ? null : c.getLong(1);
                secret = c.isNull(2) ? null : c.getBlob(2);
            }
            boolean src = false;
            try (Cursor c = db.rawQuery("PRAGMA table_info(flag_overrides)", null)) {
                while (c.moveToNext()) if ("source".equals(c.getString(1))) src = true;
            }
            hasSource = src;
        }

        Long overrideId(String name) {
            try (Cursor c = db.rawQuery("SELECT override_id FROM flag_overrides WHERE config_package_id = ? "
                    + "AND account_id = 0 AND active IS 1 AND name = ?", new String[]{"" + configPackageId, name})) {
                return c.moveToFirst() ? c.getLong(0) : null;
            }
        }

        Object overrideValue(String name) {
            try (Cursor c = db.rawQuery("SELECT value, type FROM flag_overrides WHERE config_package_id = ? "
                    + "AND account_id = 0 AND active IS 1 AND name = ?", new String[]{"" + configPackageId, name})) {
                if (!c.moveToFirst()) return null;
                int t = c.getInt(1);
                return t == DB_BOOL ? (Object) (c.getLong(0) != 0) : c.getString(0);
            }
        }

        void upsertOverride(String name, Object value) {
            ContentValues v = new ContentValues();
            if (value instanceof Boolean) { v.put("value", ((Boolean) value) ? 1 : 0); v.put("type", DB_BOOL); }
            else if (value instanceof Long) { v.put("value", (Long) value); v.put("type", DB_LONG); }
            else { v.put("value", value.toString()); v.put("type", DB_STRING); }
            Long id = overrideId(name);
            if (id != null) {
                db.update("flag_overrides", v, "override_id = ?", new String[]{"" + id});
            } else {
                v.put("config_package_id", configPackageId);
                v.put("account_id", 0);
                v.put("name", name);
                if (hasSource) v.put("source", 0);
                id = db.insertOrThrow("flag_overrides", null, v);
            }
            if (committedState != null) {
                db.execSQL("INSERT OR IGNORE INTO experiment_states_to_overrides (experiment_state_id, override_id) "
                        + "VALUES (?, ?)", new Object[]{committedState, id});
            }
            db.execSQL("INSERT OR IGNORE INTO flag_overrides_to_commit (override_id, config_package_id, account_id) "
                    + "VALUES (?, ?, 0)", new Object[]{id, configPackageId});
        }

        int removeOverrides(List<String> names) {
            int n = 0;
            for (String name : names) {
                try (Cursor c = db.rawQuery("SELECT override_id FROM flag_overrides WHERE config_package_id = ? AND name = ?",
                        new String[]{"" + configPackageId, name})) {
                    while (c.moveToNext()) {
                        String id = "" + c.getLong(0);
                        db.delete("experiment_states_to_overrides", "override_id = ?", new String[]{id});
                        db.delete("flag_overrides_to_commit", "override_id = ?", new String[]{id});
                        n += db.delete("flag_overrides", "override_id = ?", new String[]{id});
                    }
                }
            }
            return n;
        }

        /** The compressed config snapshot Play Services shares with Android Auto, or null. */
        File sharedSnapshot() throws Exception {
            if (secret == null) return null;
            File dir = new File(SHARED_ROOT + Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP));
            File[] files = dir.listFiles();
            if (files == null) return null;
            for (File f : files) {
                if (!f.getName().endsWith(".pb")) continue;
                byte[] data = readAll(f);
                try {
                    Container c = Container.parse(data);
                    if (c.header().startsWith(" " + AA + " ")) return f;
                } catch (RuntimeException ignored) {
                    // not a snapshot container
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- shared snapshot format

    /** 8-byte header, then length-prefixed records: [0] = metadata proto, [1] = raw-deflate compact flags. */
    static class Container {
        byte[] head;
        List<byte[]> records = new ArrayList<>();

        static Container parse(byte[] d) {
            Container c = new Container();
            c.head = java.util.Arrays.copyOf(d, 8);
            int[] pos = {8};
            while (pos[0] < d.length) {
                int len = (int) varint(d, pos);
                if (len < 0 || pos[0] + len > d.length) throw new IllegalArgumentException("bad record");
                c.records.add(java.util.Arrays.copyOfRange(d, pos[0], pos[0] + len));
                pos[0] += len;
            }
            if (c.records.size() < 2) throw new IllegalArgumentException("not a snapshot");
            return c;
        }

        String header() {
            byte[] r = records.get(0);
            int[] pos = {0};
            while (pos[0] < r.length) {
                long key = varint(r, pos);
                int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (wire == 2) {
                    int len = (int) varint(r, pos);
                    if (field == 1) return new String(r, pos[0], len, StandardCharsets.UTF_8);
                    pos[0] += len;
                } else if (wire == 0) varint(r, pos);
                else if (wire == 1) pos[0] += 8;
                else if (wire == 5) pos[0] += 4;
                else throw new IllegalArgumentException("wire " + wire);
            }
            return "";
        }

        byte[] toBytes() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(head);
            for (byte[] r : records) { out.write(encVarint(r.length)); out.write(r); }
            return out.toByteArray();
        }
    }

    /** name -> {snapType, value}; value is Boolean, Long, Double, String or byte[]. */
    static Map<String, Object[]> readSnapshot(File f) throws Exception {
        Container c = Container.parse(readAll(f));
        byte[] raw = inflate(c.records.get(1));
        Map<String, Object[]> flags = new TreeMap<>();
        int[] pos = {0};
        long count = varint(raw, pos);
        for (long i = 0; i < count; i++) {
            int t = raw[pos[0]++] & 0xff;
            int nlen = (int) varint(raw, pos);
            String name = new String(raw, pos[0], nlen, StandardCharsets.UTF_8);
            pos[0] += nlen;
            Object v;
            switch (t) {
                case SNAP_FALSE: v = Boolean.FALSE; break;
                case SNAP_TRUE: v = Boolean.TRUE; break;
                case SNAP_LONG: v = varint(raw, pos); break;
                case SNAP_DOUBLE: {
                    long bits = 0;
                    for (int b = 0; b < 8; b++) bits |= (raw[pos[0] + b] & 0xffL) << (8 * b);
                    pos[0] += 8;
                    v = Double.longBitsToDouble(bits);
                    break;
                }
                case SNAP_STRING:
                case SNAP_BYTES: {
                    int len = (int) varint(raw, pos);
                    byte[] b = java.util.Arrays.copyOfRange(raw, pos[0], pos[0] + len);
                    pos[0] += len;
                    v = t == SNAP_STRING ? new String(b, StandardCharsets.UTF_8) : b;
                    break;
                }
                default: throw new IllegalStateException("Unknown flag type " + t + " for " + name);
            }
            flags.put(name, new Object[]{t, v});
        }
        if (pos[0] != raw.length) throw new IllegalStateException("Snapshot has trailing data; format changed?");
        return flags;
    }

    static void writeSnapshot(File f, Map<String, Object[]> flags) throws Exception {
        byte[] original = readAll(f);
        Container c = Container.parse(original);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(encVarint(flags.size()));
        for (Map.Entry<String, Object[]> e : flags.entrySet()) {
            int t = (Integer) e.getValue()[0];
            Object v = e.getValue()[1];
            if (t == SNAP_FALSE || t == SNAP_TRUE) t = ((Boolean) v) ? SNAP_TRUE : SNAP_FALSE;
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            raw.write(t);
            raw.write(encVarint(name.length));
            raw.write(name);
            if (t == SNAP_LONG) raw.write(encVarint((Long) v));
            else if (t == SNAP_DOUBLE) {
                long bits = Double.doubleToRawLongBits((Double) v);
                for (int b = 0; b < 8; b++) raw.write((int) (bits >>> (8 * b)) & 0xff);
            } else if (t == SNAP_STRING || t == SNAP_BYTES) {
                byte[] b = v instanceof String ? ((String) v).getBytes(StandardCharsets.UTF_8) : (byte[]) v;
                raw.write(encVarint(b.length));
                raw.write(b);
            }
        }
        c.records.set(1, deflate(raw.toByteArray()));
        byte[] out = c.toBytes();
        // Writing into the existing file keeps its owner and SELinux label.
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(out); }
        // Sanity check: re-read what we wrote.
        readSnapshot(f);
    }

    static Object[] toSnap(Object v) {
        if (v instanceof Boolean) return new Object[]{((Boolean) v) ? SNAP_TRUE : SNAP_FALSE, v};
        if (v instanceof Long) return new Object[]{SNAP_LONG, v};
        return new Object[]{SNAP_STRING, v.toString()};
    }

    static void saveOriginals(Map<String, Object[]> snap) throws Exception {
        STATE_DIR.mkdirs();
        Properties p = new Properties();
        for (String name : allNames()) {
            Object[] s = snap.get(name);
            if (s == null) p.setProperty(name, "absent");
            else if (s[1] instanceof Boolean) p.setProperty(name, "bool:" + s[1]);
            else if (s[1] instanceof Long) p.setProperty(name, "long:" + s[1]);
            else if (s[1] instanceof String) p.setProperty(name, "str:" + s[1]);
            else p.setProperty(name, "absent"); // not a type we set
        }
        try (FileOutputStream out = new FileOutputStream(ORIGINALS)) { p.store(out, "aa-tweak original values"); }
    }

    static Object[] decodeSaved(String s) {
        String type = s.substring(0, s.indexOf(':')), val = s.substring(s.indexOf(':') + 1);
        switch (type) {
            case "bool": return toSnap(Boolean.parseBoolean(val));
            case "long": return toSnap(Long.parseLong(val));
            default: return toSnap(val);
        }
    }

    // ---------------------------------------------------------------- helpers

    static long varint(byte[] d, int[] pos) {
        long r = 0;
        int shift = 0;
        while (true) {
            int b = d[pos[0]++] & 0xff;
            r |= (long) (b & 0x7f) << shift;
            if (b < 0x80) return r;
            shift += 7;
        }
    }

    static byte[] encVarint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            if ((v & ~0x7FL) == 0) { out.write((int) v); return out.toByteArray(); }
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
    }

    static byte[] inflate(byte[] comp) throws Exception {
        Inflater inf = new Inflater(true);
        byte[] in = java.util.Arrays.copyOf(comp, comp.length + 1); // raw inflate wants one spare byte
        inf.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }

    static byte[] deflate(byte[] raw) {
        Deflater def = new Deflater(9, true);
        def.setInput(raw);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!def.finished()) out.write(buf, 0, def.deflate(buf));
        def.end();
        return out.toByteArray();
    }

    static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static void sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        drain(p);
        p.waitFor();
    }

    static String shOut(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = drain(p);
        p.waitFor();
        return s;
    }

    static String drain(Process p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
