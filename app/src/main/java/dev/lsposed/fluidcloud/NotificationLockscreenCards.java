package dev.lsposed.fluidcloud;

import android.app.Notification;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Routes the converted lockscreen card to ColorOS's notification list. */
final class NotificationLockscreenCards {
    private static final int NOTIFICATION_LIST = 16;

    static void preparePreview(Notification preview) {
        // The native template receives the original simple actions. It cannot host
        // Android's inline reply or authenticate an action on the source app's behalf.
        var buttons = new ArrayList<Notification.Action>(2);
        if (preview.actions != null) for (var button : preview.actions) {
            if (button == null || button.actionIntent == null || button.isAuthenticationRequired()
                    || button.getRemoteInputs() != null || button.getDataOnlyRemoteInputs() != null
                    || button.isContextual() || android.text.TextUtils.isEmpty(button.title)) continue;
            buttons.add(button);
            if (buttons.size() == 2) break;
        }
        preview.actions = buttons.toArray(Notification.Action[]::new);
        // ColorOS opens the body via contentIntent and handles keyguard dismissal
        // for the target activity. A full-screen alert is not a body click target.
        preview.deleteIntent = null;
        preview.fullScreenIntent = null;
        preview.contentView = null;
        preview.bigContentView = null;
        preview.headsUpContentView = null;
    }

    static JSONObject placement(JSONObject current, boolean shade, boolean locked) {
        if (current == null && !shade) return null;
        JSONObject options = current == null ? new JSONObject() : current;
        try {
            if (shade) listHost(options, "showHostMap");
            if (locked && shade) listHost(options, "lockScreenShowHostMap");
            else options.remove("lockScreenShowHostMap");
            return options;
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    private static void listHost(JSONObject options, String key) throws JSONException {
        JSONArray hosts = options.optJSONArray(key);
        if (hosts == null || hosts.length() != 1 || hosts.optInt(0) != NOTIFICATION_LIST)
            options.put(key, new JSONArray().put(NOTIFICATION_LIST));
    }
}
