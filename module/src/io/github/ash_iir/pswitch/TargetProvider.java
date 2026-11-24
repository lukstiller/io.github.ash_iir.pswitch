package io.github.ash_iir.pswitch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Local IPC between the companion app and its Play Store hook. */
public class TargetProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.ash_iir.pswitch.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/target");

    @Override public boolean onCreate() { return true; }

    private boolean isPlayStoreCaller() {
        try {
            return Binder.getCallingUid() == getContext().getPackageManager()
                    .getApplicationInfo("com.android.vending", 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (Binder.getCallingUid() != Process.myUid() && !isPlayStoreCaller())
            throw new SecurityException("Unauthorized caller");
        if (!URI.equals(uri))
            throw new IllegalArgumentException("Unknown URI");
        SharedPreferences p = getContext().getSharedPreferences("pswitch", 0);
        MatrixCursor c = new MatrixCursor(new String[]{"email", "version"});
        c.addRow(new Object[]{p.getString("target", null), p.getLong("version", 0)});
        return c;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"publishAccounts".equals(method))
            return super.call(method, arg, extras);
        if (!isPlayStoreCaller())
            throw new SecurityException("Unauthorized caller");
        ArrayList<String> input = extras == null ? null : extras.getStringArrayList("accounts");
        if (input == null)
            throw new IllegalArgumentException("Missing account list");
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        for (String name : input) {
            if (name != null && name.indexOf('@') > 0)
                names.add(name);
        }
        getContext().getSharedPreferences("pswitch", 0).edit()
                .putStringSet("accounts", names).remove("hookCurrent").apply();
        return new Bundle();
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/pswitch_target"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
