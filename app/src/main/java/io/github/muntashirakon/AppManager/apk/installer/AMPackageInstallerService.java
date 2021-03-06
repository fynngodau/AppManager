/*
 * Copyright (C) 2020 Muntashir Al-Islam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.muntashirakon.AppManager.apk.installer;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import io.github.muntashirakon.AppManager.BuildConfig;
import io.github.muntashirakon.AppManager.R;
import io.github.muntashirakon.AppManager.apk.ApkFile;
import io.github.muntashirakon.AppManager.main.MainActivity;
import io.github.muntashirakon.AppManager.runner.Runner;
import io.github.muntashirakon.AppManager.types.FreshFile;
import io.github.muntashirakon.AppManager.utils.AppPref;
import io.github.muntashirakon.AppManager.utils.NotificationUtils;
import io.github.muntashirakon.AppManager.utils.PackageUtils;

import static io.github.muntashirakon.AppManager.utils.PackageUtils.PACKAGE_STAGING_DIRECTORY;

public class AMPackageInstallerService extends IntentService {
    public static final String EXTRA_APK_FILE = "EXTRA_APK_FILE";
    public static final String EXTRA_APP_LABEL = "EXTRA_APP_LABEL";
    public static final String EXTRA_CLOSE_APK_FILE = "EXTRA_CLOSE_APK_FILE";
    public static final String CHANNEL_ID = BuildConfig.APPLICATION_ID + ".channel.INSTALL";
    public static final int NOTIFICATION_ID = 3;

    public AMPackageInstallerService() {
        super("AMPackageInstallerService");
    }

    private boolean completed = false;
    private boolean closeApkFile = false;
    private String appLabel;
    private String packageName;
    private ApkFile apkFile;
    private NotificationCompat.Builder builder;
    private NotificationManager notificationManager;
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, @NonNull Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(AMPackageInstaller.ACTION_INSTALL_COMPLETED)) {
                String packageName = intent.getStringExtra(AMPackageInstaller.EXTRA_PACKAGE_NAME);
                if (packageName != null && packageName.equals(AMPackageInstallerService.this.packageName)) {
                    sendNotification(intent.getIntExtra(AMPackageInstaller.EXTRA_STATUS, AMPackageInstaller.STATUS_FAILURE_INVALID), intent.getStringExtra(AMPackageInstaller.EXTRA_OTHER_PACKAGE_NAME));
                    if (closeApkFile && apkFile != null) apkFile.close();
                    completed = true;
                }
            }
        }
    };

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, 0);
        builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(null)
                .setContentText(getString(R.string.install_in_progress))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setSubText(getText(R.string.package_installer))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .setContentIntent(pendingIntent);
        startForeground(NOTIFICATION_ID, builder.build());
        registerReceiver(broadcastReceiver, new IntentFilter(AMPackageInstaller.ACTION_INSTALL_COMPLETED));
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) return;
        apkFile = intent.getParcelableExtra(EXTRA_APK_FILE);
        if (apkFile == null) return;
        packageName = apkFile.getPackageName();
        appLabel = intent.getStringExtra(EXTRA_APP_LABEL);
        closeApkFile = intent.getBooleanExtra(EXTRA_CLOSE_APK_FILE, false);
        // Set package name in the ongoing notification
        builder.setContentTitle(appLabel);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
        // Start writing Obb files
        new Thread(() -> {
            if (apkFile.hasObb()) {
                boolean tmpCloseApkFile = closeApkFile;
                // Disable closing apk file in case the install is finished already.
                closeApkFile = false;
                if (!apkFile.extractObb()) {
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this,
                            R.string.failed_to_extract_obb_files, Toast.LENGTH_LONG).show());
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this,
                            R.string.obb_files_extracted_successfully, Toast.LENGTH_LONG).show());
                }
                if (!completed) {
                    // Reset close apk file if the install isn't completed
                    closeApkFile = tmpCloseApkFile;
                } else {
                    // Install completed, close apk file if requested
                    if (tmpCloseApkFile) apkFile.close();
                }
            }
        }).start();
        // Get staging apk files
        List<ApkFile.Entry> entries = apkFile.getSelectedEntries();
        File[] stagingApkFiles;
        try {
            stagingApkFiles = getStagingApkFiles(entries);
        } catch (IOException e) {
            e.printStackTrace();
            AMPackageInstaller.sendCompletedBroadcast(packageName, AMPackageInstaller.STATUS_FAILURE_INVALID);
            return;
        }
        // Install package
        if (AppPref.isRootOrAdbEnabled()) {
            PackageInstallerShell.getInstance().installMultiple(stagingApkFiles, packageName);
            for (File file : stagingApkFiles) {
                if (file instanceof FreshFile) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            }
        } else {
            PackageInstallerNoRoot.getInstance().installMultiple(stagingApkFiles, packageName);
        }
        int count = 18000000; // 5 hours
        int interval = 100; // 100 millis
        while (!completed && count != 0) {
            try {
                Thread.sleep(interval);
                count -= interval;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }

    private void sendNotification(int status, String blockingPackage) {
        NotificationCompat.Builder builder = NotificationUtils.getHighPriorityNotificationBuilder(this);
        builder.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setTicker(appLabel)
                .setContentTitle(appLabel)
                .setSubText(getText(R.string.package_installer))
                .setContentText(getStringFromStatus(status, blockingPackage));
        NotificationUtils.displayHighPriorityNotification(builder.build());
    }

    @NonNull
    private String getStringFromStatus(int status, String blockingPackage) {
        switch (status) {
            case AMPackageInstaller.STATUS_SUCCESS:
                return getString(R.string.package_name_is_installed_successfully, appLabel);
            case AMPackageInstaller.STATUS_FAILURE_ABORTED:
                return getString(R.string.installer_error_aborted);
            case AMPackageInstaller.STATUS_FAILURE_BLOCKED:
                String blocker = getString(R.string.installer_error_blocked_device);
                if (blockingPackage != null) {
                    blocker = PackageUtils.getPackageLabel(getPackageManager(), blockingPackage);
                }
                return getString(R.string.installer_error_blocked, blocker);
            case AMPackageInstaller.STATUS_FAILURE_CONFLICT:
                return getString(R.string.installer_error_conflict);
            case AMPackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return getString(R.string.installer_error_incompatible);
            case AMPackageInstaller.STATUS_FAILURE_INVALID:
                return getString(R.string.installer_error_bad_apks);
            case AMPackageInstaller.STATUS_FAILURE_STORAGE:
                return getString(R.string.installer_error_storage);
            case AMPackageInstaller.STATUS_FAILURE_SECURITY:
                return getString(R.string.installer_error_security);
            case AMPackageInstaller.STATUS_FAILURE_SESSION_CREATE:
                return getString(R.string.installer_error_session_create);
            case AMPackageInstaller.STATUS_FAILURE_SESSION_WRITE:
                return getString(R.string.installer_error_session_write);
            case AMPackageInstaller.STATUS_FAILURE_SESSION_COMMIT:
                return getString(R.string.installer_error_session_commit);
            case AMPackageInstaller.STATUS_FAILURE_SESSION_ABANDON:
                return getString(R.string.installer_error_session_abandon);
            case AMPackageInstaller.STATUS_FAILURE_INCOMPATIBLE_ROM:
                return getString(R.string.installer_error_lidl_rom);
        }
        return getString(R.string.installer_error_generic);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID,
                    "Install Progress", NotificationManager.IMPORTANCE_LOW);
            notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    @NonNull
    public static File[] getStagingApkFiles(@NonNull List<ApkFile.Entry> apkEntries) throws IOException {
        File[] apkFiles = new File[apkEntries.size()];
        if (AppPref.isRootOrAdbEnabled() && PACKAGE_STAGING_DIRECTORY.exists()) {
            File tmpSource;
            for (int i = 0; i < apkFiles.length; ++i) {
                tmpSource = apkEntries.get(i).source;
                apkFiles[i] = new FreshFile(PACKAGE_STAGING_DIRECTORY, tmpSource.getName());
                if (!Runner.runCommand(String.format("cp \"%s\" \"%s\"", tmpSource.getAbsolutePath(),
                        apkFiles[i].getAbsolutePath())).isSuccessful()) {
                    throw new IOException("Failed to copy files to the staging directory.");
                }
            }
        } else {
            for (int i = 0; i < apkFiles.length; ++i) apkFiles[i] = apkEntries.get(i).source;
        }
        return apkFiles;
    }
}
