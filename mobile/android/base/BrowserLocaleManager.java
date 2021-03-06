/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class manages persistence, application, and otherwise handling of
 * user-specified locales.
 *
 * Of note:
 *
 * * It's a singleton, because its scope extends to that of the application,
 *   and definitionally all changes to the locale of the app must go through
 *   this.
 * * It's lazy.
 * * It has ties into the Gecko event system, because it has to tell Gecko when
 *   to switch locale.
 * * It relies on using the SharedPreferences file owned by the browser (in
 *   Fennec's case, "GeckoApp") for performance.
 */
public class BrowserLocaleManager implements LocaleManager {
    private static final String LOG_TAG = "GeckoLocales";

    private static final String EVENT_LOCALE_CHANGED = "Locale:Changed";
    private static final String PREF_LOCALE = "locale";

    // This is volatile because we don't impose restrictions
    // over which thread calls our methods.
    private volatile Locale currentLocale = null;

    private AtomicBoolean inited = new AtomicBoolean(false);
    private boolean systemLocaleDidChange = false;
    private BroadcastReceiver receiver;

    private static AtomicReference<LocaleManager> instance = new AtomicReference<LocaleManager>();

    public static LocaleManager getInstance() {
        LocaleManager localeManager = instance.get();
        if (localeManager != null) {
            return localeManager;
        }

        localeManager = new BrowserLocaleManager();
        if (instance.compareAndSet(null, localeManager)) {
            return localeManager;
        } else {
            return instance.get();
        }
    }

    /**
     * Gecko uses locale codes like "es-ES", whereas a Java {@link Locale}
     * stringifies as "es_ES".
     *
     * This method approximates the Java 7 method <code>Locale#toLanguageTag()</code>.
     *
     * @return a locale string suitable for passing to Gecko.
     */
    public static String getLanguageTag(final Locale locale) {
        // If this were Java 7:
        // return locale.toLanguageTag();

        String language = locale.getLanguage();  // Can, but should never be, an empty string.
        // Modernize certain language codes.
        if (language.equals("iw")) {
            language = "he";
        } else if (language.equals("in")) {
            language = "id";
        } else if (language.equals("ji")) {
            language = "yi";
        }

        String country = locale.getCountry();    // Can be an empty string.
        if (country.equals("")) {
            return language;
        }
        return language + "-" + country;
    }

    private static Locale parseLocaleCode(final String localeCode) {
        int index;
        if ((index = localeCode.indexOf('-')) != -1 ||
            (index = localeCode.indexOf('_')) != -1) {
            final String langCode = localeCode.substring(0, index);
            final String countryCode = localeCode.substring(index + 1);
            return new Locale(langCode, countryCode);
        } else {
            return new Locale(localeCode);
        }
    }

    /**
     * Ensure that you call this early in your application startup,
     * and with a context that's sufficiently long-lived (typically
     * the application context).
     *
     * Calling multiple times is harmless.
     */
    @Override
    public void initialize(final Context context) {
        if (!inited.compareAndSet(false, true)) {
            return;
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                systemLocaleDidChange = true;
            }
        };
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_LOCALE_CHANGED));
    }

    @Override
    public boolean systemLocaleDidChange() {
        return systemLocaleDidChange;
    }

    /**
     * Every time the system gives us a new configuration, it
     * carries the external locale. Fix it.
     */
    @Override
    public void correctLocale(Context context, Resources res, Configuration config) {
        final Locale current = getCurrentLocale(context);
        if (current == null) {
            return;
        }

        // I know it's tempting to short-circuit here if the config seems to be
        // up-to-date, but the rest is necessary.

        config.locale = current;

        // The following two lines are heavily commented in case someone
        // decides to chase down performance improvements and decides to
        // question what's going on here.
        // Both lines should be cheap, *but*...

        // This is unnecessary for basic string choice, but it almost
        // certainly comes into play when rendering numbers, deciding on RTL,
        // etc. Take it out if you can prove that's not the case.
        Locale.setDefault(current);

        // This seems to be a no-op, but every piece of documentation under the
        // sun suggests that it's necessary, and it certainly makes sense.
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    @Override
    public String getAndApplyPersistedLocale(Context context) {
        initialize(context);

        final long t1 = android.os.SystemClock.uptimeMillis();
        final String localeCode = getPersistedLocale(context);
        if (localeCode == null) {
            return null;
        }

        // Note that we don't tell Gecko about this. We notify Gecko when the
        // locale is set, not when we update Java.
        final String resultant = updateLocale(context, localeCode);

        if (resultant == null) {
            // Update the configuration anyway.
            updateConfiguration(context, currentLocale);
        }

        final long t2 = android.os.SystemClock.uptimeMillis();
        Log.i(LOG_TAG, "Locale read and update took: " + (t2 - t1) + "ms.");
        return resultant;
    }

    /**
     * Returns the set locale if it changed.
     *
     * Always persists and notifies Gecko.
     */
    @Override
    public String setSelectedLocale(Context context, String localeCode) {
        final String resultant = updateLocale(context, localeCode);

        // We always persist and notify Gecko, even if nothing seemed to
        // change. This might happen if you're picking a locale that's the same
        // as the current OS locale. The OS locale might change next time we
        // launch, and we need the Gecko pref and persisted locale to have been
        // set by the time that happens.
        persistLocale(context, localeCode);

        // Tell Gecko.
        GeckoEvent ev = GeckoEvent.createBroadcastEvent(EVENT_LOCALE_CHANGED, BrowserLocaleManager.getLanguageTag(getCurrentLocale(context)));
        GeckoAppShell.sendEventToGecko(ev);

        return resultant;
    }

    /**
     * This is public to allow for an activity to force the
     * current locale to be applied if necessary (e.g., when
     * a new activity launches).
     */
    @Override
    public void updateConfiguration(Context context, Locale locale) {
        Resources res = context.getResources();
        Configuration config = res.getConfiguration();
        config.locale = locale;
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    private SharedPreferences getSharedPreferences(Context context) {
        return GeckoSharedPrefs.forApp(context);
    }

    private String getPersistedLocale(Context context) {
        final SharedPreferences settings = getSharedPreferences(context);
        final String locale = settings.getString(PREF_LOCALE, "");

        if ("".equals(locale)) {
            return null;
        }
        return locale;
    }

    private void persistLocale(Context context, String localeCode) {
        final SharedPreferences settings = getSharedPreferences(context);
        settings.edit().putString(PREF_LOCALE, localeCode).commit();
    }

    private Locale getCurrentLocale(Context context) {
        if (currentLocale != null) {
            return currentLocale;
        }

        final String current = getPersistedLocale(context);
        if (current == null) {
            return null;
        }
        return currentLocale = parseLocaleCode(current);
    }

    /**
     * Updates the Java locale and the Android configuration.
     *
     * Returns the persisted locale if it differed.
     *
     * Does not notify Gecko.
     */
    private String updateLocale(Context context, String localeCode) {
        // Fast path.
        final Locale defaultLocale = Locale.getDefault();
        if (defaultLocale.toString().equals(localeCode)) {
            return null;
        }

        final Locale locale = parseLocaleCode(localeCode);

        // Fast path.
        if (defaultLocale.equals(locale)) {
            return null;
        }

        Locale.setDefault(locale);
        currentLocale = locale;

        // Update resources.
        updateConfiguration(context, locale);

        return locale.toString();
    }
}
