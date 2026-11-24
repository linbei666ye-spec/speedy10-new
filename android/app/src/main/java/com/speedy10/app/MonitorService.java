
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {

    private static final String CHANNEL_ID = "speedy10_monitor";
    private static final int NOTIFICATION_ID = 1001;

    private static final String API_URL =
            "https://speedy10-data.lin-bei666ye.workers.dev/";

    private static final String HISTORY_URL =
            API_URL + "?action=history&days=100";

    private static final String PREFS = "speedy10_monitor";
    private static final String KEY_PREDICTIONS = "predictions_json";
    private static final String KEY_HISTORY = "history_json";
    private static final String KEY_LATEST_ISSUE = "latest_issue";
    private static final String KEY_LATEST_CODE = "latest_code";

    private static final String[] MODEL_KEYS = {
            "frequency", "omission", "trend", "multi",
            "cycle", "transition", "adaptive", "ensemble"
    };

    private ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    private List<Draw> draws = new ArrayList<>();
    private JSONArray predictions = new JSONArray();

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
        loadSavedData();

        startForeground(
                NOTIFICATION_ID,
                buildNotification("正在启动后台预测监控…")
        );

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                this::checkLatestDraw,
                0,
                60,
                TimeUnit.SECONDS
        );
    }

    // 网络读取

    private JSONObject fetchJSON(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");

            int status = connection.getResponseCode();
            if (status != 200) {
                throw new Exception("服务器返回：" + status);
            }

            StringBuilder response = new StringBuilder();
            try (
                    InputStream input = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8)
                    )
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            return new JSONObject(response.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject unwrapLatest(JSONObject root) {
        JSONObject result = root.optJSONObject("result");
        if (result != null) {
            JSONObject data = result.optJSONObject("data");
            if (data != null) return data;
        }

        JSONObject data = root.optJSONObject("data");
        if (data != null) return data;

        return root;
    }

    private JSONArray extractHistory(JSONObject root) {
        JSONArray array = root.optJSONArray("data");
        if (array != null) return array;

        JSONObject result = root.optJSONObject("result");
        if (result != null) {
            array = result.optJSONArray("data");
            if (array != null) return array;

            JSONObject data = result.optJSONObject("data");
            if (data != null) {
                array = data.optJSONArray("list");
                if (array != null) return array;
            }
        }

        return root.optJSONArray("list");
    }

    // 开奖数据处理

    private static class Draw {
        String issue;
        String time;
        int[] code;

        Draw(String issue, String time, int[] code) {
            this.issue = issue;
            this.time = time;
            this.code = code;
        }
    }

    private int[] validCode(String value) {
        if (value == null || value.isEmpty()) return null;

        String[] parts = value.split("[,，\\s-]+");
        if (parts.length != 10) return null;

        int[] numbers = new int[10];
        boolean[] seen = new boolean[11];

        try {
            for (int i = 0; i < 10; i++) {
                int n = Integer.parseInt(parts[i]);
                if (n < 1 || n > 10 || seen[n]) return null;
                numbers[i] = n;
                seen[n] = true;
            }
        } catch (Exception e) {
            return null;
        }

        return numbers;
    }

    private Draw normalizeDraw(JSONObject object) {
        if (object == null) return null;

        String issue = object.optString(
                "preDrawIssue",
                object.optString("issue", "")
        );

        String time = object.optString(
                "preDrawTime",
                object.optString("drawTime", "")
        );

        String code = object.optString(
                "preDrawCode",
                object.optString(
                        "drawCode",
                        object.optString("code", "")
                )
        );

        int[] numbers = validCode(code);
        if (issue.isEmpty() || numbers == null) return null;

        return new Draw(issue, time, numbers);
    }

    private int compareIssue(String a, String b) {
        try {
            return new BigInteger(b).compareTo(new BigInteger(a));
        } catch (Exception e) {
            return b.compareTo(a);
        }
    }

    private void mergeDraws(List<Draw> incoming) {
        Map<String, Draw> map = new HashMap<>();

        for (Draw d : draws) map.put(d.issue, d);
        for (Draw d : incoming) map.put(d.issue, d);

        List<Draw> merged = new ArrayList<>(map.values());
        Collections.sort(merged, (a, b) -> compareIssue(a.issue, b.issue));

        if (merged.size() > 500) {
            merged = new ArrayList<>(merged.subList(0, 500));
        }

        draws = merged;
    }

    // 八种预测模型

    private static final int MODEL_COUNT = 8;

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isTargetPosition(int position, String kind) {
        if ("A".equals(kind)) return position >= 0 && position < 3;
        if ("B".equals(kind)) return position >= 0 && position < 7;
        if ("C".equals(kind)) return position >= 1 && position < 8;
        return false;
    }

    private double rate(List<Integer> positions, int window, String kind) {
        int count = Math.min(window, positions.size());
        if (count == 0) return 0.0;

        int hits = 0;
        for (int i = 0; i < count; i++) {
            if (isTargetPosition(positions.get(i), kind)) hits++;
        }

        return hits / (double) count;
    }

    private int median(List<Integer> values) {
        if (values.isEmpty()) return 1;

        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) return sorted.get(mid);

        return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    private int zone(int position) {
        if (position < 3) return 0;
        if (position < 7) return 1;
        return 2;
    }

    private double[][] scoreNumbers(List<Draw> data, String kind) {
        double base = "A".equals(kind) ? 0.30 : 0.70;
        double[][] scores = new double[11][MODEL_COUNT];

        for (int n = 1; n <= 10; n++) {
            List<Integer> positions = new ArrayList<>();

            for (Draw d : data) {
                int position = 10;
                for (int i = 0; i < 10; i++) {
                    if (d.code[i] == n) {
                        position = i;
                        break;
                    }
                }
                positions.add(position);
            }

            double r5 = rate(positions, 5, kind);
            double r10 = rate(positions, 10, kind);
            double r20 = rate(positions, 20, kind);
            double r30 = rate(positions, 30, kind);
            double r60 = rate(positions, 60, kind);
            double r100 = rate(positions, 100, kind);

            int gap = positions.size();
            for (int i = 0; i < positions.size(); i++) {
                if (isTargetPosition(positions.get(i), kind)) {
                    gap = i;
                    break;
                }
            }

            double frequency = r10 * 0.50 + r30 * 0.30 + r100 * 0.20;
            double expectedGap = 1.0 / Math.max(base, 0.01);
            double omission = Math.exp(
                    -Math.abs(gap - expectedGap) / expectedGap
            );
            double trend = clamp(0.5 + (r10 - r30), 0.0, 1.0);
            double multi = r5 * 0.40 + r20 * 0.35 + r60 * 0.25;

            List<Integer> hitIndexes = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                if (isTargetPosition(positions.get(i), kind)) {
                    hitIndexes.add(i);
                }
            }

            List<Integer> intervals = new ArrayList<>();
            for (int i = 1; i < hitIndexes.size(); i++) {
                intervals.add(hitIndexes.get(i) - hitIndexes.get(i - 1));
            }

            int typicalCycle = median(intervals);
            double cycle = Math.exp(
                    -Math.abs(gap - typicalCycle)
                    / (double) Math.max(1, typicalCycle)
            );

            int previousZone = positions.isEmpty() ? 2 : zone(positions.get(0));
            int transitionHits = 0;
            int transitionTotal = 0;

            for (int i = 1; i < positions.size(); i++) {
                if (zone(positions.get(i)) == previousZone) {
                    transitionTotal++;
                    if (isTargetPosition(positions.get(i - 1), kind)) {
                        transitionHits++;
                    }
                }
            }

            double transition =
                    (transitionHits + base * 8.0) / (transitionTotal + 8.0);

            double adaptive =
                    frequency * 0.45
                    + trend * 0.20
                    + transition * 0.25
                    + multi * 0.10;

            double ensemble =
                    frequency * 0.18
                    + omission * 0.04
                    + trend * 0.14
                    + multi * 0.18
                    + cycle * 0.08
                    + transition * 0.22
                    + adaptive * 0.16;

            scores[n][0] = frequency;
            scores[n][1] = omission;
            scores[n][2] = trend;
            scores[n][3] = multi;
            scores[n][4] = cycle;
            scores[n][5] = transition;
            scores[n][6] = adaptive;
            scores[n][7] = ensemble;
        }

        return scores;
    }

    private int rankFor(List<Draw> data, String kind, int model) {
        double[][] scores = scoreNumbers(data, kind);

        int bestNumber = 1;
        double bestScore = scores[1][model];

        for (int n = 2; n <= 10; n++) {
            double score = scores[n][model];
            if (score > bestScore) {
                bestScore = score;
                bestNumber = n;
            }
        }

        return bestNumber;
    }

    private static class ModelResult {
        int hit;
        int total;
        double rate;
        double adjusted;
    }

    private ModelResult[] testModels(List<Draw> data, String kind) {
        double base = "A".equals(kind) ? 0.30 : 0.70;
        int minTrain = 30;
        int maxTests = Math.max(0, Math.min(120, data.size() - minTrain));

        ModelResult[] result = new ModelResult[MODEL_COUNT];
        for (int m = 0; m < MODEL_COUNT; m++) {
            result[m] = new ModelResult();
        }

        for (int i = 0; i < maxTests; i++) {
            List<Draw> train = data.subList(i + 1, data.size());
            if (train.size() < minTrain) continue;

            int[] actual = data.get(i).code;

            for (int m = 0; m < MODEL_COUNT; m++) {
                int predicted = rankFor(train, kind, m);
                boolean hit = false;

                for (int j = 0; j < 10; j++) {
                    if (isTargetPosition(j, kind) && actual[j] == predicted) {
                        hit = true;
                        break;
                    }
                }

                result[m].total++;
                if (hit) result[m].hit++;
            }
        }

        for (int m = 0; m < MODEL_COUNT; m++) {
            ModelResult x = result[m];
            x.rate = x.total == 0 ? 0.0 : x.hit / (double) x.total;
            x.adjusted = x.total == 0
                    ? base
                    : (x.rate * x.total + base * 30.0) / (x.total + 30.0);
        }

        return result;
    }

    private int chooseModel(List<Draw> data, String kind) {
        ModelResult[] tests = testModels(data, kind);
        int best = 0;

        for (int i = 1; i < MODEL_COUNT; i++) {
            if (tests[i].adjusted > tests[best].adjusted) {
                best = i;
            }
        }

        return best;
    }

    // D系统：最近100期指定名次九码分析

    private static class NineChoice {
        int position;
        int excluded;
        double rate;

        NineChoice(int position, int excluded, double rate) {
            this.position = position;
            this.excluded = excluded;
            this.rate = rate;
        }
    }

    private NineChoice chooseNinePosition(List<Draw> source, String seed) {
        int sampleSize = Math.min(100, source.size());

        if (sampleSize == 0) {
            return new NineChoice(1, 1, 0.0);
        }

        double bestRate = -1.0;
        List<Integer> bestPositions = new ArrayList<>();
        int[] excludedByPosition = new int[10];
        double[] rateByPosition = new double[10];

        for (int pos = 0; pos < 10; pos++) {
            int[] counts = new int[11];

            for (int i = 0; i < sampleSize; i++) {
                int n = source.get(i).code[pos];
                if (n >= 1 && n <= 10) counts[n]++;
            }

            int minCount = Integer.MAX_VALUE;
            List<Integer> excludedCandidates = new ArrayList<>();

            for (int n = 1; n <= 10; n++) {
                if (counts[n] < minCount) {
                    minCount = counts[n];
                    excludedCandidates.clear();
                    excludedCandidates.add(n);
                } else if (counts[n] == minCount) {
                    excludedCandidates.add(n);
                }
            }

            int hash = (seed + "-" + (pos + 1)).hashCode();
            int excludedIndex = Math.floorMod(
                    hash, excludedCandidates.size()
            );

            int excluded = excludedCandidates.get(excludedIndex);

            double currentRate =
                    1.0 - counts[excluded] / (double) sampleSize;

            excludedByPosition[pos] = excluded;
            rateByPosition[pos] = currentRate;

            int comparison = Double.compare(currentRate, bestRate);

            if (comparison > 0) {
                bestRate = currentRate;
                bestPositions.clear();
                bestPositions.add(pos);
            } else if (comparison == 0) {
                bestPositions.add(pos);
            }
        }

        int seedHash = seed.hashCode();
        int chosenIndex = Math.floorMod(seedHash, bestPositions.size());
        int chosenPosition = bestPositions.get(chosenIndex);

        return new NineChoice(
                chosenPosition + 1,
                excludedByPosition[chosenPosition],
                rateByPosition[chosenPosition]
        );
    }

    // 预测记录

    private boolean predictionExists(String target, String kind) {
        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p == null) continue;

            if (target.equals(p.optString("target"))
                    && kind.equals(p.optString("kind"))) {
                return true;
            }
        }

        return false;
    }

    private void addPrediction(String kind, String target, int number, int model) {
        try {
            JSONObject p = new JSONObject();
            p.put("kind", kind);
            p.put("target", target);
            p.put("base", draws.get(0).issue);
            p.put("number", number);
            p.put("model", MODEL_KEYS[model]);
            p.put("result", "pending");
            p.put("created", System.currentTimeMillis());
            prependPrediction(p);
        } catch (Exception ignored) {
        }
    }

    private void addNineNumberPrediction(String target) {
        try {
            NineChoice choice = chooseNinePosition(draws, target);

            JSONArray numbers = new JSONArray();

            for (int n = 1; n <= 10; n++) {
                if (n != choice.excluded) {
                    numbers.put(n);
                }
            }

            JSONObject p = new JSONObject();
            p.put("kind", "D");
            p.put("target", target);
            p.put("base", draws.get(0).issue);
            p.put("position", choice.position);
            p.put("numbers", numbers);
            p.put("excluded", choice.excluded);
            p.put("positionRate", choice.rate);
            p.put("model", "历史位置九码分析");
            p.put("result", "pending");
            p.put("created", System.currentTimeMillis());

            prependPrediction(p);
        } catch (Exception ignored) {
        }
    }

    // 将尚未开奖的旧版D记录升级为新版

    private boolean upgradePendingNinePredictions(String target) {
        boolean changed = false;

        if (draws.isEmpty()) return false;

        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p == null) continue;

            if (!"D".equals(p.optString("kind"))) continue;
            if (!"pending".equals(p.optString("result"))) continue;
            if (!target.equals(p.optString("target"))) continue;

            int oldPosition = p.optInt("position", 0);

            if (oldPosition >= 1 && oldPosition <= 10) {
                continue;
            }

            try {
                NineChoice choice = chooseNinePosition(draws, target);
                JSONArray numbers = new JSONArray();

                for (int n = 1; n <= 10; n++) {
                    if (n != choice.excluded) numbers.put(n);
                }

                p.put("base", draws.get(0).issue);
                p.put("position", choice.position);
                p.put("numbers", numbers);
                p.put("excluded", choice.excluded);
                p.put("positionRate", choice.rate);
                p.put("model", "历史位置九码分析");
                p.put("upgraded", true);

                changed = true;
            } catch (Exception ignored) {
            }
        }

        return changed;
    }

    private void prependPrediction(JSONObject p) {
        JSONArray updated = new JSONArray();
        updated.put(p);

        for (int i = 0; i < predictions.length(); i++) {
            updated.put(predictions.opt(i));
        }

        predictions = updated;

        if (predictions.length() > 1000) {
            JSONArray trimmed = new JSONArray();
            for (int i = 0; i < 1000; i++) {
                trimmed.put(predictions.opt(i));
            }
            predictions = trimmed;
        }
    }

    private void makeNextPredictions(String targetIssue) {
        if (targetIssue == null || targetIssue.isEmpty()) return;

        if (draws.size() < 30) {
            updateNotification("历史数据不足30期，暂不能生成预测");
            return;
        }

        for (String kind : new String[]{"A", "B", "C"}) {
            if (!predictionExists(targetIssue, kind)) {
                int model = chooseModel(draws, kind);
                int number = rankFor(draws, kind, model);
                addPrediction(kind, targetIssue, number, model);
            }
        }

        boolean upgraded = upgradePendingNinePredictions(targetIssue);

        if (!predictionExists(targetIssue, "D")) {
            addNineNumberPrediction(targetIssue);
        }

        if (upgraded) {
            updateNotification("D系统旧版待开奖记录已升级");
        }
    }

    // 自动结算

    private boolean containsTargetPosition(int[] code, int number, String kind) {
        for (int i = 0; i < code.length; i++) {
            if (isTargetPosition(i, kind) && code[i] == number) return true;
        }
        return false;
    }

    private boolean settlePredictions() {
        Map<String, Draw> byIssue = new HashMap<>();
        for (Draw d : draws) byIssue.put(d.issue, d);

        boolean changed = false;

        for (int i = 0; i < predictions.length(); i++) {
            JSONObject p = predictions.optJSONObject(i);
            if (p == null || !"pending".equals(p.optString("result"))) continue;

            Draw d = byIssue.get(p.optString("target"));
            if (d == null) continue;

            String kind = p.optString("kind");
            boolean hit = false;

            if ("D".equals(kind)) {
                JSONArray numbers = p.optJSONArray("numbers");
                int position = p.optInt("position", 0);

                if (numbers != null && position >= 1 && position <= 10) {
                    int actualAtPosition = d.code[position - 1];

                    for (int j = 0; j < numbers.length(); j++) {
                        if (numbers.optInt(j, -1) == actualAtPosition) {
                            hit = true;
                            break;
                        }
                    }
                } else if (numbers != null) {
                    // 旧版没有position的记录，保留旧版全位置结算方式
                    for (int j = 0; j < numbers.length() && !hit; j++) {
                        int n = numbers.optInt(j, -1);

                        for (int actual : d.code) {
                            if (actual == n) {
                                hit = true;
                                break;
                            }
                        }
                    }
                }
            } else {
                hit = containsTargetPosition(
                        d.code,
                        p.optInt("number", -1),
                        kind
                );
            }

            try {
                p.put("result", hit ? "hit" : "miss");

                JSONArray actual = new JSONArray();
                for (int n : d.code) actual.put(n);

                p.put("actual", actual);
                p.put("settledAt", d.time);
                changed = true;
            } catch (Exception ignored) {
            }
        }

        return changed;
    }

    // 本机持久化

    private void loadSavedData() {
        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        String savedPredictions =
                prefs.getString(KEY_PREDICTIONS, "[]");

        String savedHistory =
                prefs.getString(KEY_HISTORY, "[]");

        try {
            predictions = new JSONArray(savedPredictions);
        } catch (Exception e) {
            predictions = new JSONArray();
        }

        try {
            JSONArray array = new JSONArray(savedHistory);
            List<Draw> loaded = new ArrayList<>();

            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                Draw d = normalizeDraw(object);
                if (d != null) loaded.add(d);
            }

            draws = loaded;
        } catch (Exception e) {
            draws = new ArrayList<>();
        }
    }

    private void saveAll() {
        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        JSONArray history = new JSONArray();

        for (Draw d : draws) {
            try {
                JSONObject object = new JSONObject();
                object.put("preDrawIssue", d.issue);
                object.put("preDrawTime", d.time);

                StringBuilder code = new StringBuilder();

                for (int i = 0; i < d.code.length; i++) {
                    if (i > 0) code.append(",");
                    if (d.code[i] < 10) code.append("0");
                    code.append(d.code[i]);
                }

                object.put("preDrawCode", code.toString());
                history.put(object);
            } catch (Exception ignored) {
            }
        }

        prefs.edit()
                .putString(KEY_PREDICTIONS, predictions.toString())
                .putString(KEY_HISTORY, history.toString())
                .apply();
    }

    // 每分钟执行

    private void checkLatestDraw() {
        synchronized (lock) {
            try {
                updateNotification("正在读取最新开奖和历史数据…");

                JSONObject latestRoot = fetchJSON(API_URL);
                JSONObject latestData = unwrapLatest(latestRoot);

                String issue = latestData.optString("preDrawIssue", "");
                String code = latestData.optString("preDrawCode", "");
                String targetIssue = latestData.optString("drawIssue", "");
                String drawTime = latestData.optString("preDrawTime", "");

                int[] latestCode = validCode(code);

                if (issue.isEmpty() || targetIssue.isEmpty() || latestCode == null) {
                    throw new Exception("最新开奖数据格式不正确");
                }

                JSONObject historyRoot = fetchJSON(HISTORY_URL);
                JSONArray historyArray = extractHistory(historyRoot);

                if (historyArray == null || historyArray.length() == 0) {
                    throw new Exception("历史开奖数据为空");
                }

                List<Draw> incoming = new ArrayList<>();

                for (int i = 0; i < historyArray.length(); i++) {
                    JSONObject object = historyArray.optJSONObject(i);
                    Draw d = normalizeDraw(object);
                    if (d != null) incoming.add(d);
                }

                if (incoming.isEmpty()) {
                    throw new Exception("没有识别到有效历史记录");
                }

                incoming.add(new Draw(issue, drawTime, latestCode));
                mergeDraws(incoming);

                boolean settled = settlePredictions();

                makeNextPredictions(targetIssue);

                saveAll();

                SharedPreferences prefs =
                        getSharedPreferences(PREFS, MODE_PRIVATE);

                prefs.edit()
                        .putString(KEY_LATEST_ISSUE, issue)
                        .putString(KEY_LATEST_CODE, code)
                        .apply();

                String message =
                        "监控正常 · 最新期 " + issue
                        + " · 下一期 " + targetIssue
                        + " · 历史 " + draws.size() + "期";

                if (settled) message += " · 已完成预测结算";

                updateNotification(message);

            } catch (Exception e) {
                updateNotification("后台读取失败，60秒后重试");
            }
        }
    }

    // 通知

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Speedy10 后台开奖监控",
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "每分钟读取开奖数据并生成A、B、C、D预测"
            );

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String message) {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("Speedy10 后台预测运行中")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true);

        return builder.build();
    }

    private void updateNotification(String messa