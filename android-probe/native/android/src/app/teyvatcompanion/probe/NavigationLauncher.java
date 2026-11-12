package app.teyvatcompanion.probe;

import android.content.Intent;
import android.os.Bundle;
import org.qtproject.qt.android.bindings.QtActivity;

public final class NavigationLauncher extends QtActivity {
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (savedState == null) {
            getWindow().getDecorView().post(() -> startActivity(
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        }
    }
}
