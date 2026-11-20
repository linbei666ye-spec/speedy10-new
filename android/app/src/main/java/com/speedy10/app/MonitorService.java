
package com.speedy10.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {

    private static final String CHANNEL_ID = "speedy10_monitor";
    private static final int NOTIFICATION_ID = 1001;

    private static final String API_URL =
            "https://speedy10-data.lin-bei666ye.workers.dev/";

    private ScheduledExecutorService scheduler;

    // 修复：使用 Context 接收 MainActivity
    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        startForeground(
                NOTIFICATION_ID,
                buildNotification("正在启动开奖监控…")
        );

        scheduler = Executors.newSingleThreadScheduledExecutor();

        scheduler.scheduleWithFixedDelay(
                this::checkLatestDraw,
                0,
                60,
                TimeUnit.SECONDS
        );
    }

    private void checkLatestDraw() {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(API_URL);
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);

            int status = connection.getResponseCode();

            if (status != 200) {
                updateNotification("连接开奖服务器失败，稍后重试");
                return;
            }

            InputStream input = connection.getInputStream();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, "UTF-8")
            );

            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            reader.close();
            input.close();

            JSONObject root = new JSONObject(response.toString());
            JSONObject data = root;

            if (root.has("result")
                    && root.opt("result") instanceof JSONObject) {

                JSONObject result = root.getJSONObject("result");

                if (result.has("data")
                        && result.opt("data") instanceof JSONObject) {
                    data = result.getJSONObject("data");
                }

            } else if (root.has("data")
                    && root.opt("data") instanceof JSONObject) {

                data = root.getJSONObject("data");
            }

            String issue = data.optString("preDrawIssue", "");
            String code = data.optString("preDrawCode", "");

            if (issue.isEmpty() || code.isEmpty()
                    || "null".equals(issue)
                    || "null".equals(code)) {

                updateNotification("已连接，等待最新开奖数据");
                return;
            }

            SharedPreferences prefs = getSharedPreferences(
                    "speedy10_monitor",
                    MODE_PRIVATE
            );

            String oldIssue = prefs.getString("latest_issue", "");

            if (!issue.equals(oldIssue)) {

                prefs.edit()
                        .putString("latest_issue", issue)
                        .putString("latest_code", code)
                        .putLong(
                                "latest_time",
                                System.currentTimeMillis()
                        )
                        .apply();

                updateNotification(
                        "发现新开奖：" + issue + "  " + code
                );

            } else {

                updateNotification(
                        "监控运行中 · 最新期号：" + issue
                );
            }

        } catch (Exception e) {

            updateNotification("网络暂时不可用，正在等待重试");

        } finally {

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Speedy10 后台开奖监控",
                    NotificationManager.IMPORTANCE_LOW
            );

            channel.setDescription("持续检查最新开奖数据");

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {

        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Speedy10 监控运行中")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true);

        return builder.build();
    }

    private void updateNotification(String message) {

        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );

        manager.notify(
                NOTIFICATION_ID,
                buildNotification(message)
        );
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
