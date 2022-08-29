package eu.siacs.conversations.utils;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.List;

public class PermissionUtils {

    public static boolean allGranted(int[] grantResults) {
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static boolean writeGranted(int[] grantResults, String[] permission) {
        for (int i = 0; i < grantResults.length; ++i) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission[i])) {
                return grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    public static String getFirstDenied(int[] grantResults, String[] permissions) {
        for (int i = 0; i < grantResults.length; ++i) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                return permissions[i];
            }
        }
        return null;
    }

    public static class PermissionResult {
        public final String[] permissions;
        public final int[] grantResults;

        public PermissionResult(String[] permissions, int[] grantResults) {
            this.permissions = permissions;
            this.grantResults = grantResults;
        }
    }

    public static PermissionResult removeBluetoothConnect(
            final String[] inPermissions, final int[] inGrantResults) {
        final List<String> outPermissions = new ArrayList<>();
        final List<Integer> outGrantResults = new ArrayList<>();
        for (int i = 0; i < Math.min(inPermissions.length, inGrantResults.length); ++i) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (inPermissions[i].equals(Manifest.permission.BLUETOOTH_CONNECT)) {
                    continue;
                }
            }
            outPermissions.add(inPermissions[i]);
            outGrantResults.add(inGrantResults[i]);
        }

        return new PermissionResult(
                outPermissions.toArray(new String[0]), Ints.toArray(outGrantResults));
    }

    public static boolean hasPermission(
            final Activity activity, final List<String> permissions, final int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final ImmutableList.Builder<String> missingPermissions = new ImmutableList.Builder<>();
            for (final String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(activity, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(permission);
                }
            }
            final ImmutableList<String> missing = missingPermissions.build();
            if (missing.size() == 0) {
                return true;
            }
            ActivityCompat.requestPermissions(
                    activity, missing.toArray(new String[0]), requestCode);
            return false;
        } else {
            return true;
        }
    }
}
