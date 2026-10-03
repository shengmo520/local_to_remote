package com.planapp.bamboo;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 青竹 的安卓外壳：WebView 加载本地页面，纯本地运行。
 * 1) 页面放在应用私有目录，内容更新只需要覆盖这个文件，不用重装 APK；
 * 2) 到点提醒交给原生闹钟 + 系统通知，关掉应用也能响；
 * 3) 备份导出/导入、检查更新走原生，避开 WebView 的限制。
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_BACKUP = 1001;
    private static final int REQ_NOTIFICATION = 2001;

    private WebView webView;
    private SharedPreferences prefs;
    private boolean askedNotification = false;

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int bgColor() {
        return Color.parseColor(isNight() ? "#0F1512" : "#F3EFE4");
    }

    private File contentDir() {
        return new File(getFilesDir(), "content");
    }

    /** 每个内容版本一个文件名：URL 每次都不同，WebView 就不可能拿缓存里的旧页面 */
    private File contentFileFor(long version) {
        return new File(contentDir(), "index-" + version + ".html");
    }

    private File currentContentFile() {
        return contentFileFor(prefs.getLong("contentVersion", AppInfo.CONTENT_VERSION));
    }

    private void copyAssetTo(File f) throws Exception {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        InputStream in = getAssets().open("index.html");
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
    }

    /** 清掉除 keep 之外的历史页面，顺带删掉旧方案留下的 index.html */
    private void cleanupOldContent(long keep) {
        File[] all = contentDir().listFiles();
        if (all == null) return;
        String keepName = contentFileFor(keep).getName();
        for (File f : all) {
            if (f.getName().equals(keepName)) continue;
            if (f.getName().startsWith("index-") || f.getName().startsWith("index.html")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 决定这次该加载哪一份页面：下载过就用下载的，APK 更新过就用 APK 里带的 */
    private void prepareContent() {
        try {
            long assetVer = AppInfo.CONTENT_VERSION;
            long saved = prefs.getLong("contentVersion", 0);
            if (saved >= assetVer && contentFileFor(saved).exists()) return;
            File f = contentFileFor(assetVer);
            if (!f.exists()) copyAssetTo(f);
            prefs.edit().putLong("contentVersion", assetVer).apply();
            cleanupOldContent(assetVer);
        } catch (Exception e) {
            toast("初始化页面失败：" + e.getMessage());
        }
    }

    private String pageUrl() {
        return Uri.fromFile(currentContentFile()).toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = ReminderReceiver.prefs(this);

        getWindow().setStatusBarColor(bgColor());
        getWindow().setNavigationBarColor(bgColor());

        prepareContent();
        ReminderReceiver.ensureChannel(this);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(bgColor());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                ReminderReceiver.scheduleAll(MainActivity.this);
                if (!askedNotification) {
                    askedNotification = true;
                    ensureNotificationPermission();
                }
            }
        });
        webView.addJavascriptInterface(new Bridge(), "PlanApp");

        setContentView(webView);
        webView.loadUrl(pageUrl());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReminderReceiver.foreground = true;
        // 回到前台时立刻查一次更新（页面里的 5 分钟节流对这里不生效）
        if (webView != null) {
            webView.postDelayed(new Runnable() {
                @Override public void run() {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "(function(){ return window.__checkUpdateNow ? window.__checkUpdateNow() : false; })()", null);
                    }
                }
            }, 1200);
        }
    }

    @Override
    protected void onPause() {
        ReminderReceiver.foreground = false;
        super.onPause();
    }

    /* ==================== 通知权限 ==================== */

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_NOTIFICATION && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            toast("没有通知权限，到点提醒只能在应用打开时生效");
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            toast("无法打开系统设置，请手动到「设置 → 应用 → 青竹 → 通知」里开启");
        }
    }

    /* ==================== 与页面通信 ==================== */

    public class Bridge {
        @JavascriptInterface
        public String versionInfo() {
            return AppInfo.SHELL_VERSION_NAME + "|" + AppInfo.SHELL_VERSION_CODE;
        }

        @JavascriptInterface
        public String contentVersion() {
            return String.valueOf(prefs.getLong("contentVersion", AppInfo.CONTENT_VERSION));
        }

        @JavascriptInterface
        public void syncReminders(final String json) {
            new Thread(new Runnable() {
                @Override public void run() { ReminderReceiver.sync(MainActivity.this, json); }
            }).start();
        }

        @JavascriptInterface
        public void checkUpdate(final String base) {
            new Thread(new Runnable() {
                @Override public void run() { doCheckUpdate(base); }
            }).start();
        }

        @JavascriptInterface
        public void downloadContent(final String url, final String version) {
            new Thread(new Runnable() {
                @Override public void run() { doDownloadContent(url, parseLong(version)); }
            }).start();
        }

        @JavascriptInterface
        public void downloadApk(final String url, final String fileName) {
            new Thread(new Runnable() {
                @Override public void run() { doDownloadApk(url, fileName); }
            }).start();
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (webView == null) return;
                    webView.clearCache(true);          // 避免又读回旧页面
                    webView.loadUrl(pageUrl());
                }
            });
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATION);
                    } else {
                        openNotificationSettings();
                    }
                }
            });
        }

        @JavascriptInterface
        public String notificationPermission() {
            if (Build.VERSION.SDK_INT < 33) return "granted";
            return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
                    ? "granted" : "denied";
        }

        @JavascriptInterface
        public void saveBackup(final String json, final String fileName) {
            runOnUiThread(new Runnable() {
                @Override public void run() { doSaveBackup(json, fileName); }
            });
        }

        @JavascriptInterface
        public void pickBackup() {
            runOnUiThread(new Runnable() {
                @Override public void run() { doPickBackup(); }
            });
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private void callJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView != null) webView.evaluateJavascript(js, null);
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /* ==================== 网络 ==================== */

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "QingZhu/" + AppInfo.SHELL_VERSION_NAME);
        return c;
    }

    private byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("服务器返回 HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    private String join(String base, String name) {
        String b = base.trim();
        if (!b.endsWith("/")) b = b + "/";
        return b + name;
    }

    /**
     * 检查更新用的候选地址：内置地址优先，另外两个作为备用。
     * CDN 对分支文件有最长 12 小时缓存，只问一个地址时可能一直拿到旧版本，导致手机端被告知「已经是最新」。
     */
    private List<String> updateSources(String base) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (base != null && base.trim().length() > 0) set.add(base.trim());
        set.add("https://fastly.jsdelivr.net/gh/shengmo520/qingzhu-plan@main/docs/");
        set.add("https://raw.githubusercontent.com/shengmo520/qingzhu-plan/main/docs/");
        return new ArrayList<String>(set);
    }

    /** 并发去问所有来源，取版本号最高的那一份；请求带上时间参数，绕过 CDN 的旧缓存 */
    private void doCheckUpdate(String base) {
        final List<String> sources = updateSources(base);
        final CountDownLatch latch = new CountDownLatch(sources.size());
        final List<String[]> got = Collections.synchronizedList(new ArrayList<String[]>());
        for (final String src : sources) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        String url = join(src, "update.json") + "?t=" + System.currentTimeMillis();
                        String body = new String(httpGetBytes(url), "UTF-8");
                        JSONObject json = new JSONObject(body);
                        long score = json.optLong("contentVersion", 0) * 1000L + json.optLong("versionCode", 0);
                        got.add(new String[]{String.valueOf(score), body, src});
                    } catch (Exception e) {
                        // 单个来源连不上不影响其它来源
                    } finally {
                        latch.countDown();
                    }
                }
            }).start();
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try { latch.await(20, TimeUnit.SECONDS); } catch (Exception e) { }
                String bestBody = null, bestBase = null;
                long bestScore = -1;
                synchronized (got) {
                    for (String[] item : got) {
                        long score = parseLong(item[0]);
                        if (bestBody == null || score > bestScore) {
                            bestScore = score;
                            bestBody = item[1];
                            bestBase = item[2];
                        }
                    }
                }
                if (bestBody != null) {
                    callJs("window.__updateInfo && window.__updateInfo(" + JSONObject.quote(bestBody)
                            + ", null, " + JSONObject.quote(bestBase) + ")");
                } else {
                    callJs("window.__updateInfo && window.__updateInfo(null, "
                            + JSONObject.quote("网络连不上更新地址") + ", " + JSONObject.quote(base) + ")");
                }
            }
        }).start();
    }

    private void doDownloadContent(String url, long version) {
        try {
            String html = new String(httpGetBytes(url), "UTF-8");
            if (html.length() < 800) throw new Exception("内容太短，可能不是有效页面");
            byte[] bytes = html.getBytes("UTF-8");
            File dst = contentFileFor(version);
            File dir = dst.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(dir, dst.getName() + ".part");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(bytes);
            out.flush();
            out.close();
            if (tmp.length() != bytes.length) throw new Exception("写入校验失败");
            if (dst.exists() && !dst.delete()) throw new Exception("无法覆盖旧页面");
            if (!tmp.renameTo(dst)) throw new Exception("写入新页面失败");
            prefs.edit().putLong("contentVersion", version).apply();
            cleanupOldContent(version);
            callJs("window.__updateContent && window.__updateContent('1', null)");
        } catch (Exception e) {
            callJs("window.__updateContent && window.__updateContent('0', "
                    + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
        }
    }

    private void doDownloadApk(String url, String fileName) {
        try {
            byte[] data = httpGetBytes(url);
            if (data.length < 10000) throw new Exception("安装包太小，下载可能不完整");
            Uri uri = saveToDownloads(data, fileName, "application/vnd.android.package-archive");
            if (uri == null) throw new Exception("无法写入下载目录");
            callJs("window.__updateApk && window.__updateApk('1', null)");
            final Uri target = uri;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(target, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("安装包已保存到「下载」，请手动点开安装");
                    }
                }
            });
        } catch (Exception e) {
            callJs("window.__updateApk && window.__updateApk('0', "
                    + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
        }
    }

    /* ==================== 备份 ==================== */

    private Uri saveToDownloads(byte[] data, String fileName, String mime) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.flush();
                os.close();
                return uri;
            }
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
            fos.close();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void doSaveBackup(String json, String fileName) {
        try {
            byte[] bytes = json.getBytes("UTF-8");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = saveToDownloads(bytes, fileName, "application/json");
                if (uri == null) { toast("导出失败：无法写入下载目录"); return; }
                toast("备份已保存到「下载」：" + fileName);
            } else {
                File dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.flush();
                fos.close();
                toast("备份已保存：" + f.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void doPickBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_BACKUP);
        } catch (Exception e) {
            toast("无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_BACKUP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                final String text = new String(bo.toByteArray(), "UTF-8");
                callJs("window.__planImport && window.__planImport(" + JSONObject.quote(text) + ")");
            } catch (Exception e) {
                toast("读取文件失败：" + e.getMessage());
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ==================== 返回键 ==================== */

    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
                "(function(){ try { return window.__planBack ? window.__planBack() : false; } catch (e) { return false; } })()",
                new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        if (!"true".equals(value)) {
                            if (webView.canGoBack()) webView.goBack();
                            else finish();
                        }
                    }
                });
    }
}
