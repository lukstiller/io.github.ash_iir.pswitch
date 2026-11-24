package io.github.ash_iir.pswitch;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String VENDING = "com.android.vending";
    private static final String ACTION_REQUEST = "io.github.ash_iir.pswitch.REQUEST_ACCOUNTS";
    private static final String PREFS = "pswitch";

    private final List<String> emails = new ArrayList<String>();
    private ArrayAdapter<String> adapter;
    private TextView status;
    private String current;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if ("accounts".equals(key))
                        loadAccounts();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("PSwitch — Play Store Account");

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(24, 22, 24, 6);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(0xFF666666);
        hint.setPadding(24, 0, 24, 12);
        hint.setText("Select an account for normal Google Play billing.\n"
                + "Experimental: verify the actual account in the purchase sheet.");

        Button refresh = new Button(this);
        refresh.setText("Refresh accounts");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
                Toast.makeText(MainActivity.this, "List refreshed", Toast.LENGTH_SHORT).show();
            }
        });

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>()) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextSize(16);
                    boolean isReset = pos == 0;
                    boolean isUsed = !isReset && emails.size() > pos - 1
                            && emails.get(pos - 1).equals(current);
                    tv.setTextColor(isReset ? 0xFF888888
                            : (isUsed ? 0xFF0B8043 : 0xFF222222));
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                apply(pos == 0 ? null : emails.get(pos - 1));
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.addView(status);
        root.addView(hint);
        // Button above the list so it never hides behind the navigation bar.
        root.addView(refresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (!hasPermission(Manifest.permission.GET_ACCOUNTS))
            askPermission(Manifest.permission.GET_ACCOUNTS);

        getSharedPreferences(PREFS, 0).registerOnSharedPreferenceChangeListener(prefsListener);
        refresh();
    }

    @Override
    protected void onDestroy() {
        getSharedPreferences(PREFS, 0).unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // Called by the framework on API 23+; kept reflection-friendly for the
    // old compile-time android jar used by this build.
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        refresh();
    }

    private boolean hasPermission(String perm) {
        try {
            java.lang.reflect.Method m = Activity.class.getMethod("checkSelfPermission", String.class);
            return ((Integer) m.invoke(this, perm)) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void askPermission(String perm) {
        try {
            java.lang.reflect.Method m = Activity.class.getMethod(
                    "requestPermissions", String[].class, int.class);
            m.invoke(this, new Object[]{new String[]{perm}, 1});
        } catch (Throwable ignored) {
        }
    }

    private void refresh() {
        loadAccounts();
        Intent req = new Intent(ACTION_REQUEST);
        req.setPackage(VENDING);
        sendBroadcast(req);
    }

    private void loadAccounts() {
        SharedPreferences sp = getSharedPreferences(PREFS, 0);
        current = sp.getString("target", null);
        emails.clear();
        Set<String> cached = sp.getStringSet("accounts", null);
        if (cached != null) {
            emails.addAll(cached);
        } else {
            try {
                Set<String> found = new LinkedHashSet<String>();
                for (Account account : AccountManager.get(this).getAccountsByType("com.google"))
                    found.add(account.name);
                emails.addAll(found);
            } catch (SecurityException ignored) {
                // Play Store will publish its own visible accounts when available.
            }
        }
        updateDisplay();
    }

    private void updateDisplay() {
        if (current != null && !emails.contains(current))
            emails.add(0, current);

        status.setText(current == null
                ? "Selected: Play Store default"
                : "Selected: " + current);

        final List<String> display = new ArrayList<String>();
        display.add("↺  Reset — use Play Store default");
        for (String e : emails)
            display.add((e.equals(current) ? "✓  " : "•  ") + e);
        adapter.clear();
        adapter.addAll(display);
        adapter.notifyDataSetChanged();
    }

    private void apply(String email) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
        if (email == null)
            e.remove("target");
        else
            e.putString("target", email);
        e.putLong("version", System.currentTimeMillis());
        e.apply();

        // The hook reads the selected account through the provider at each resolution.
        Toast.makeText(this, email == null
                        ? "Reset to Play Store default"
                        : "Account selected; verify in checkout",
                Toast.LENGTH_SHORT).show();
        refresh();
    }
}
