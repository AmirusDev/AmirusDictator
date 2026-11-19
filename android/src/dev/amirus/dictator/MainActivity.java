package dev.amirus.dictator;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/** Setup steps (microphone, enable keyboard, pick keyboard) and settings for the voice keyboard. */
public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private InputMethodManager imm;
    private Button micStep, enableStep, pickStep;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        text("Speak in বাংলা or English and it is typed into any app.", 16, false);
        heading("Set up");
        micStep = step(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        enableStep = step(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        pickStep = step(v -> imm.showInputMethodPicker());
        text("After that, tap ⌨ on the keyboard (or the keyboard icon in the navigation bar) to switch between this and your normal keyboard.", 13, false);

        heading("Try it");
        EditText tryBox = new EditText(this);
        tryBox.setHint("Tap here, then tap the mic…");
        tryBox.setMinLines(2);
        tryBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(tryBox);

        heading("Text clean-up");
        toggle("Auto-correct spacing and punctuation", "autocorrect", true);
        toggle("Capitalize English", "capitalize", true);
        toggle("Use দাঁড়ি (।) in Bengali", "dari", true);
        toggle("Spoken punctuation (“দাঁড়ি”, “কমা”, “নতুন লাইন”, “comma”…)", "commands", true);
        toggle("End each pause with । or .", "autoEnd", false);
        choice("Numbers", "digits", new String[]{"As recognized", "০১২ in Bengali mode", "Always 012"}, new String[]{"keep", "bn", "en"});

        heading("Listening");
        toggle("Keep listening through pauses", "keepListening", true);
        toggle("Start listening when the keyboard opens", "autoStart", false);
        choice("Bengali accent", "bnLocale", new String[]{"Bangladesh", "India"}, new String[]{"bn-BD", "bn-IN"});
        choice("English accent", "enLocale", new String[]{"US", "UK", "India"}, new String[]{"en-US", "en-GB", "en-IN"});

        heading("My corrections");
        text("One per line: heard = wanted", 13, false);
        EditText rules = new EditText(this);
        rules.setHint("whats app = WhatsApp");
        rules.setMinLines(3);
        rules.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        rules.setText(prefs.getString("replacements", ""));
        rules.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString("replacements", s.toString()).apply();
            }
        });
        root.addView(rules);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSteps();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshSteps();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        boolean denied = results.length > 0 && results[0] != PackageManager.PERMISSION_GRANTED;
        if (denied && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // The system will not ask again, so send the user to the app's permission page.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
        }
        refreshSteps();
    }

    private void refreshSteps() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean enabled = false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) enabled = true;
        }
        boolean picked;
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            InputMethodInfo current = imm.getCurrentInputMethodInfo();
            picked = current != null && getPackageName().equals(current.getPackageName());
        } else {
            String current = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            picked = current != null && current.startsWith(getPackageName() + "/");
        }
        micStep.setText((mic ? "✓  " : "1.  ") + "Allow the microphone");
        enableStep.setText((enabled ? "✓  " : "2.  ") + "Turn on the Amirus Dictator keyboard");
        pickStep.setText((picked ? "✓  " : "3.  ") + "Switch to it");
        micStep.setEnabled(!mic);
        pickStep.setEnabled(enabled);
    }

    // ---------- small view helpers ----------
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
        return t;
    }

    private void heading(String value) {
        text(value, 20, true).setPadding(0, dp(24), 0, dp(6));
    }

    private Button step(View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setOnClickListener(onClick);
        root.addView(b);
        return b;
    }

    private void toggle(String title, final String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(title);
        s.setTextSize(16);
        s.setPadding(0, dp(12), 0, dp(12));
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        root.addView(s);
    }

    private void choice(String title, final String key, String[] labels, final String[] values) {
        text(title, 16, false).setPadding(0, dp(12), 0, 0);
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String current = prefs.getString(key, values[0]);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) spinner.setSelection(i);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(key, values[position]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(spinner);
    }
}
