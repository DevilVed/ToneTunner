package com.devilved.tonetunner;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;

/** Locks out permissions that have no matching receiver or package-enumeration feature. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AndroidManifestPermissionsTest {
    @Test public void manifestDoesNotRequestPackageEnumerationOrBootReceiverPermission()
            throws PackageManager.NameNotFoundException {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);

        assertFalse("The app has no package-enumeration feature",
                requests(packageInfo, "android.permission.QUERY_ALL_PACKAGES"));
        assertFalse("The app declares no boot receiver",
                requests(packageInfo, "android.permission.RECEIVE_BOOT_COMPLETED"));
    }

    private static boolean requests(PackageInfo packageInfo, String permission) {
        if (packageInfo.requestedPermissions == null) return false;
        for (String requested : packageInfo.requestedPermissions) {
            if (permission.equals(requested)) return true;
        }
        return false;
    }
}
