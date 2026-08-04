package ai.edgez.androiddevtools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Starts the EdgeZ relay when these sources are embedded in the Expo Go application. */
public final class EdgezStartupProvider extends ContentProvider {
    private static final String TAG = "AndroidDevTools";

    @Override
    public boolean onCreate() {
        if (getContext() == null) {
            return false;
        }
        try {
            ProxyService.startIfConfigured(getContext());
        } catch (Throwable throwable) {
            Log.w(TAG, "Embedded Expo runtime could not start the EdgeZ relay", throwable);
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("EdgeZ startup provider is not a data provider");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("EdgeZ startup provider is not a data provider");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("EdgeZ startup provider is not a data provider");
    }
}
