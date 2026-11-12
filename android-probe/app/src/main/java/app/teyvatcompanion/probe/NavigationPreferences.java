package app.teyvatcompanion.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.Locale;

final class NavigationPreferences {
    static final String LANGUAGE = "language";
    static final String REGION = "region";
    static final String NEAR = "near";
    static final String AUTO = "auto";
    static final String OVERLAY = "overlay";
    static final String SIZE = "size";
    static final String OPACITY = "opacity";
    static final String POSITION = "position";

    private NavigationPreferences() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("navigation_ui", Context.MODE_PRIVATE);
    }

    static boolean automaticCollection(SharedPreferences preferences) {
        return preferences.getBoolean(AUTO, true);
    }

    static Context localized(Context context) {
        String language = get(context).getString(LANGUAGE, "en");
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return context.createConfigurationContext(configuration);
    }

    static String status(Context context, NativeNavigation.Guidance guidance) {
        if (guidance == null) { return context.getString(R.string.ready); }
        switch (guidance.state) {
            case "guiding": return context.getString(R.string.guiding);
            case "near_target": return context.getString(R.string.near_chest);
            case "heading_unknown": return context.getString(R.string.heading_unknown);
            case "no_target": return context.getString(R.string.no_target);
            case "anchored": return context.getString(R.string.anchored);
            case "unsupported_hud": return context.getString(R.string.unsupported_hud);
            case "waiting_for_map":
            case "position_unknown": return context.getString(R.string.find_position);
            case "stale_target":
            case "cannot_confirm": return context.getString(R.string.stale_target);
            default: return guidance.status;
        }
    }

    static String target(Context context, String name) {
        switch (name) {
            case "Common Chest": return context.getString(R.string.chest_common);
            case "Exquisite Chest": return context.getString(R.string.chest_exquisite);
            case "Precious Chest": return context.getString(R.string.chest_precious);
            case "Luxurious Chest": return context.getString(R.string.chest_luxurious);
            case "Remarkable Chest": return context.getString(R.string.chest_remarkable);
            default: return name;
        }
    }
}
