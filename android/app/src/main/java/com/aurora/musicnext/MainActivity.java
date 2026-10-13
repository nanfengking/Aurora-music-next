package com.aurora.musicnext;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle state) {
        registerPlugin(AuroraMusicPlugin.class);
        super.onCreate(state);
        applyTextScale();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); applyTextScale();
    }
    private void applyTextScale() {
        if (getBridge() == null) return;
        int zoom = Math.round(getResources().getConfiguration().fontScale * 100);
        getBridge().getWebView().getSettings().setTextZoom(Math.max(50, Math.min(300, zoom)));
    }
}
