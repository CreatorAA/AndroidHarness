package com.androidharness.fixture.phoneuse;

import android.app.Activity;
import android.app.DirectAction;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Positive control. Only a Direct Action can display this in-memory, non-sensitive receipt. */
public final class DirectActionFixtureActivity extends Activity {
    private static final String ACTION = "show_test_receipt";
    private boolean shown;
    private String publicQuery;
    private TextView message;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().hasExtra("offer_fixture_map")) {
            getPackageManager().setComponentEnabledSetting(new ComponentName(getPackageName(), getPackageName() + ".TestMap"),
                getIntent().getBooleanExtra("offer_fixture_map", false)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
        }
        shown = state != null && state.getBoolean("shown");
        Uri data = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && data != null &&
                "geo".equals(data.getScheme()) && data.toString().startsWith("geo:0,0?q="))
            publicQuery = Uri.decode(data.toString().substring("geo:0,0?q=".length()));
        message = new TextView(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        message.setPadding(padding, padding, padding, padding);
        message.setTextSize(24);
        setContentView(message);
        render();
    }

    @Override public void onGetDirectActions(CancellationSignal cancel, Consumer<List<DirectAction>> reply) {
        if (cancel.isCanceled()) return;
        // Some Android 14 vendor frameworks dereference the optional locus while parceling the action.
        reply.accept(shown ? Collections.emptyList() : Collections.singletonList(new DirectAction.Builder(ACTION)
            .setLocusId(new android.content.LocusId("fixture_receipt")).build()));
    }

    @Override public void onPerformDirectAction(String id, Bundle arguments, CancellationSignal cancel, Consumer<Bundle> reply) {
        if (cancel.isCanceled()) return;
        Bundle result = new Bundle();
        if (!shown && ACTION.equals(id) && (arguments == null || arguments.isEmpty())) {
            shown = true;
            render();
            result.putString("status", "shown");
            reply.accept(result);
            if (getVoiceInteractor() != null) getVoiceInteractor().notifyDirectActionsChanged();
        } else {
            result.putString("status", "not_available");
            reply.accept(result);
        }
    }

    private void render() {
        message.setText(publicQuery != null ? "Public map receipt: " + publicQuery :
            shown ? "Direct Action receipt: shown once" : "Direct Action fixture: ready");
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("shown", shown);
    }
}
