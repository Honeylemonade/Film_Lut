package com.filmlut.android;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.filmlut.android.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "film_lut_android";
    private static final String PREF_SERVER_URL = "server_url";

    private ActivityMainBinding binding;
    private SharedPreferences preferences;
    private ValueCallback<Uri[]> pendingFileChooserCallback;
    private ActivityResultLauncher<String[]> filePickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                this::handleFilePickerResult
        );

        setupToolbarActions();
        setupServerForm();
        setupWebView();
        loadOrPromptServerUrl();
    }

    private void setupToolbarActions() {
        binding.serverButton.setOnClickListener(v -> showServerSetup(loadSavedServerUrl(), true));
        binding.reloadButton.setOnClickListener(v -> binding.webView.reload());
        binding.retryButton.setOnClickListener(v -> tryLoadSavedUrl());
        binding.saveServerButton.setOnClickListener(v -> saveServerUrlFromInput());
        binding.emulatorHintButton.setOnClickListener(v -> {
            binding.serverUrlInput.setText(BuildConfig.DEFAULT_SERVER_URL);
            binding.serverUrlInput.setSelection(binding.serverUrlInput.getText() == null ? 0 : binding.serverUrlInput.getText().length());
        });
    }

    private void setupServerForm() {
        binding.serverDescription.setText(
                "这个安卓版本先作为客户端使用，连接你电脑上正在运行的 Film LUT 服务。\n\n" +
                        "模拟器可用: " + BuildConfig.DEFAULT_SERVER_URL + "\n" +
                        "真机请填电脑局域网地址，例如: http://192.168.1.23:8787/"
        );
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = binding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true);

        binding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;
                filePickerLauncher.launch(new String[]{"image/*", "*/*"});
                return true;
            }
        });

        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                binding.progressBar.setVisibility(View.VISIBLE);
                binding.errorGroup.setVisibility(View.GONE);
                binding.webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                binding.progressBar.setVisibility(View.GONE);
                binding.currentServerLabel.setText(getString(R.string.connected_server, url));
            }

            @Override
            public void onReceivedError(WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError(error.getDescription() == null ? "无法连接到服务。" : error.getDescription().toString());
                }
            }
        });
    }

    private void loadOrPromptServerUrl() {
        String serverUrl = loadSavedServerUrl();
        if (TextUtils.isEmpty(serverUrl)) {
            showServerSetup(BuildConfig.DEFAULT_SERVER_URL, false);
            return;
        }
        loadServerUrl(serverUrl);
    }

    private void tryLoadSavedUrl() {
        String serverUrl = loadSavedServerUrl();
        if (TextUtils.isEmpty(serverUrl)) {
            showServerSetup(BuildConfig.DEFAULT_SERVER_URL, true);
            return;
        }
        loadServerUrl(serverUrl);
    }

    private void saveServerUrlFromInput() {
        String rawUrl = binding.serverUrlInput.getText() == null ? "" : binding.serverUrlInput.getText().toString();
        String normalizedUrl = normalizeServerUrl(rawUrl);
        if (TextUtils.isEmpty(normalizedUrl)) {
            Toast.makeText(this, "服务地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        preferences.edit().putString(PREF_SERVER_URL, normalizedUrl).apply();
        loadServerUrl(normalizedUrl);
    }

    private void loadServerUrl(@NonNull String url) {
        binding.setupGroup.setVisibility(View.GONE);
        binding.errorGroup.setVisibility(View.GONE);
        binding.webView.setVisibility(View.VISIBLE);
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.webView.loadUrl(url);
        binding.serverUrlInput.setText(url);
    }

    private void showServerSetup(@NonNull String suggestion, boolean keepCurrentPage) {
        binding.serverUrlInput.setText(suggestion);
        binding.currentServerLabel.setText(getString(R.string.not_connected));
        binding.setupGroup.setVisibility(View.VISIBLE);
        binding.errorGroup.setVisibility(View.GONE);
        binding.progressBar.setVisibility(View.GONE);
        if (!keepCurrentPage) {
            binding.webView.setVisibility(View.GONE);
        }
    }

    private void showLoadError(@NonNull String errorText) {
        binding.progressBar.setVisibility(View.GONE);
        binding.errorMessage.setText(
                String.format(Locale.getDefault(),
                        "加载失败: %s\n\n请确认电脑上的服务已经启动，并且手机能访问到这个地址。",
                        errorText)
        );
        binding.errorGroup.setVisibility(View.VISIBLE);
        binding.webView.setVisibility(View.GONE);
    }

    @NonNull
    private String loadSavedServerUrl() {
        return preferences.getString(PREF_SERVER_URL, "");
    }

    @NonNull
    private String normalizeServerUrl(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        if (!value.endsWith("/")) {
            value = value + "/";
        }
        return value;
    }

    private void handleFilePickerResult(@Nullable List<Uri> uris) {
        if (pendingFileChooserCallback == null) {
            return;
        }
        if (uris == null || uris.isEmpty()) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
            return;
        }

        List<Uri> persistableUris = new ArrayList<>();
        for (Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            persistableUris.add(uri);
            final int flags = (IntentFlags.READ | IntentFlags.WRITE);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {
            }
        }
        pendingFileChooserCallback.onReceiveValue(persistableUris.toArray(new Uri[0]));
        pendingFileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        binding.webView.destroy();
        super.onDestroy();
    }

    private static final class IntentFlags {
        private static final int READ = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
        private static final int WRITE = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

        private IntentFlags() {
        }
    }
}
