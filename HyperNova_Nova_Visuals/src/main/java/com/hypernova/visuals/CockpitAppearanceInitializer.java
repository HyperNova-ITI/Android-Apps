package com.hypernova.visuals;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * Replays the remembered day/night choice once per process, before the first activity is built.
 *
 * <p>A content provider is used purely for its lifecycle: Android creates providers after
 * {@code Application.onCreate} and before any activity, which is exactly the window in which the
 * night mode has to be set for the first screen to inflate the right palette. Doing this from a
 * library means none of the six cockpit apps needs its own Application subclass, and the three
 * that already have one keep it untouched.
 *
 * <p>It stores nothing and answers nothing; every query method is a no-op.
 */
public final class CockpitAppearanceInitializer extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            CockpitAppearance.restore(context);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
