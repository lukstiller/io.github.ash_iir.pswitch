package io.github.ash_iir.pswitch;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Experimental, version-specific account selection for Play Store 54.4.11-34.
 * Only substitute an existing device account in the normal billing resolver.
 * Do not rewrite checkout display state, authorization results or receipts.
 */
public class XposedInit extends XposedModule {
    private static final String TAG = "PSwitch";
    private static final String VENDING = "com.android.vending";
    private static final int SUPPORTED_VERSION = 85441140;
    private static final String ACTION_REQUEST = "io.github.ash_iir.pswitch.REQUEST_ACCOUNTS";
    private static final Uri PROVIDER_URI =
            Uri.parse("content://io.github.ash_iir.pswitch.provider/target");

    private boolean initialized;
    private boolean applicationHookInstalled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "API " + getApiVersion() + " loaded in " + param.getProcessName());
    }

    @Override
    public synchronized void onPackageReady(PackageReadyParam param) {
        if (!VENDING.equals(param.getPackageName()) || applicationHookInstalled)
            return;
        final ClassLoader loader = param.getClassLoader();
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hook(onCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object app = chain.getThisObject();
                            if (app instanceof Context) {
                                Context ctx = (Context) app;
                                if (VENDING.equals(ctx.getPackageName()))
                                    initialize(ctx, loader);
                            }
                            return chain.proceed();
                        }
                    });
            applicationHookInstalled = true;
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "Application hook unavailable: " + e.getClass().getSimpleName());
        }
    }

    private synchronized void initialize(Context ctx, ClassLoader loader) {
        if (initialized)
            return;
        initialized = true;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(VENDING, 0);
            if (info.versionCode != SUPPORTED_VERSION) {
                log(Log.WARN, TAG, "Unsupported Play Store version: " + info.versionCode);
                return;
            }
            installBillingHook(ctx, loader);
            log(Log.INFO, TAG, "Billing account selection hook installed for " + SUPPORTED_VERSION);
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "Billing hook unavailable: " + e.getClass().getSimpleName());
        }
        // Account picker can still display accounts if the hook is unavailable.
        registerRefresh(ctx);
        publishAccounts(ctx);
    }

    private void installBillingHook(final Context ctx, ClassLoader loader) throws Exception {
        // Verified against the DEX for this exact version. Do not guess on other builds.
        Class<?> resolver = Class.forName("tub", false, loader);
        Class<?> request = Class.forName("tcv", false, loader);
        Class<?> result = Class.forName("tef", false, loader);
        Class<?> optional = Class.forName("j$.util.Optional", false, loader);

        final Method resolve = resolver.getDeclaredMethod("b", request, optional,
                Context.class, boolean.class);
        if (resolve.getReturnType() != result)
            throw new NoSuchMethodException("Unexpected resolver return type");

        final Field requestBundle = request.getDeclaredField("b");
        if (requestBundle.getType() != Bundle.class)
            throw new NoSuchFieldException("Unexpected request bundle type");
        requestBundle.setAccessible(true);
        final Method isPresent = optional.getMethod("isPresent");
        final Method get = optional.getMethod("get");
        final Method of = optional.getMethod("of", Object.class);

        hook(resolve).intercept(new Hooker() {
            @Override
            public Object intercept(Chain chain) throws Throwable {
                Object[] args = null;
                try {
                    Object req = chain.getArg(0);
                    Object original = chain.getArg(1);
                    if (req != null && original != null) {
                        Bundle extras = (Bundle) requestBundle.get(req);
                        // Leave explicit account requests and missing-account paths intact.
                        if ((extras == null || !extras.containsKey("accountName"))
                                && Boolean.TRUE.equals(isPresent.invoke(original))) {
                            Object existing = get.invoke(original);
                            if (existing instanceof Account
                                    && "com.google".equals(((Account) existing).type)) {
                                Account target = findSelectedAccount(ctx);
                                if (target != null && !target.equals(existing)) {
                                    Object replacement = of.invoke(null, target);
                                    args = chain.getArgs().toArray(new Object[0]);
                                    args[1] = replacement;
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    // Fail closed to the original billing behavior; no account details in logs.
                    log(Log.WARN, TAG, "Account selection skipped: "
                            + e.getClass().getSimpleName());
                    args = null;
                }
                if (args == null)
                    return chain.proceed();
                log(Log.INFO, TAG, "Selected device account supplied to billing resolver");
                // Run the original resolver exactly once, preserving all result/error handling.
                return chain.proceed(args);
            }
        });
    }

    private Account findSelectedAccount(Context ctx) {
        String target = null;
        Cursor cursor = null;
        try {
            cursor = ctx.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int i = cursor.getColumnIndex("email");
                if (i >= 0 && !cursor.isNull(i))
                    target = cursor.getString(i);
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (cursor != null)
                cursor.close();
        }
        if (target == null)
            return null;
        for (Account account : AccountManager.get(ctx).getAccountsByType("com.google")) {
            if (target.equals(account.name))
                return account;
        }
        return null;
    }

    private void registerRefresh(Context ctx) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REQUEST.equals(intent.getAction()))
                    publishAccounts(context);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REQUEST);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Method method = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                method.invoke(ctx, receiver, filter, 2); // RECEIVER_EXPORTED
            } else {
                ctx.registerReceiver(receiver, filter);
            }
        } catch (Throwable e) {
            log(Log.WARN, TAG, "Refresh receiver unavailable: " + e.getClass().getSimpleName());
        }
    }

    private void publishAccounts(Context ctx) {
        try {
            ArrayList<String> names = new ArrayList<String>();
            for (Account account : AccountManager.get(ctx).getAccountsByType("com.google"))
                names.add(account.name);
            Bundle data = new Bundle();
            data.putStringArrayList("accounts", names);
            // Local provider validates the caller UID; no account-list broadcast.
            ctx.getContentResolver().call(PROVIDER_URI, "publishAccounts", null, data);
            log(Log.INFO, TAG, "Account list refreshed (" + names.size() + ")");
        } catch (Throwable e) {
            log(Log.WARN, TAG, "Account list refresh unavailable: " + e.getClass().getSimpleName());
        }
    }
}
