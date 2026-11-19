package dev.amirus.dictator;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** A voice keyboard: pick it in any app, tap the mic, and speech is typed into the focused field. */
public class DictatorIME extends InputMethodService implements RecognitionListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable relisten = this::listen;
    private SharedPreferences prefs;
    private SpeechRecognizer recognizer;
    private boolean want, bn = true, composing;
    private int emptyRounds, errorRounds;
    // Android 13+ can keep one recognition session open across pauses, so nothing is lost between phrases.
    // Turned off for good if the phone's speech service rejects it.
    private boolean segmented = Build.VERSION.SDK_INT >= 33, segmentSeen;
    private TextView status, langKey, endKey;
    private MicView mic;
    private int cKey, cInk;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    /** A voice keyboard is still wanted when a physical keyboard is attached, so always show it. */
    @Override
    public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }

    // ---------- keyboard UI ----------
    @Override
    public View onCreateInputView() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int cBg = dark ? 0xFF111413 : 0xFFF5F3EE;
        cKey = dark ? 0xFF242927 : 0xFFFFFFFF;
        cInk = dark ? 0xFFECEAE4 : 0xFF1D1C1A;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        root.setPadding(dp(6), dp(6), dp(6), dp(10));

        status = new TextView(this);
        status.setTextColor(dark ? 0xFF9A9890 : 0xFF6D6B65);
        status.setTextSize(13);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(2), dp(8), dp(4));
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout top = row(root);
        langKey = key("", v -> toggleLang());
        langKey.setTextSize(16);
        add(top, langKey, 1);
        mic = new MicView(this, dark ? 0xFF14B8A6 : 0xFF0F766E, 0xFFD92D20);
        mic.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (want) stop();
            else start();
        });
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(68), dp(68));
        micLp.setMargins(dp(18), dp(2), dp(18), dp(2));
        top.addView(mic, micLp);
        TextView switchKey = key("⌨", v -> switchKeyboard());
        switchKey.setContentDescription("Switch keyboard");
        switchKey.setOnLongClickListener(v -> {
            picker();
            return true;
        });
        add(top, switchKey, 1);

        LinearLayout bottom = row(root);
        add(bottom, key(",", v -> typeKey(",")), 1);
        endKey = key("।", v -> typeKey(endKey.getText().toString()));
        add(bottom, endKey, 1);
        add(bottom, key("?", v -> typeKey("?")), 1);
        TextView space = key("space", v -> typeKey(" "));
        space.setTextSize(13);
        add(bottom, space, 2.4f);
        TextView back = key("⌫", null);
        back.setContentDescription("Backspace");
        back.setOnTouchListener(this::onBackspaceTouch);
        add(bottom, back, 1.2f);
        TextView enter = key("⏎", v -> enter());
        enter.setContentDescription("Enter");
        add(bottom, enter, 1.2f);

        refresh();
        return root;
    }

    private LinearLayout row(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void add(LinearLayout row, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(v, lp);
    }

    private TextView key(String label, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(cInk);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(cKey);
        shape.setCornerRadius(dp(10));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080), shape, null));
        if (onClick != null) {
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onClick.onClick(v);
            });
        }
        return t;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        if (status == null) return;
        langKey.setText(bn ? "বাংলা" : "English");
        endKey.setText(bn && prefs.getBoolean("dari", true) ? "।" : ".");
        mic.setLive(want);
        setStatus(want ? listeningText() : "Tap the mic and speak");
    }

    private String listeningText() {
        return "Listening · " + (bn ? "বাংলা" : "English");
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        bn = !"en".equals(prefs.getString("lang", "bn"));
        composing = false;
        refresh();
        if (!want && !restarting && prefs.getBoolean("autoStart", false)) start();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        endComposing();
        if (want) {
            want = false;
            handler.removeCallbacks(relisten);
            if (recognizer != null) recognizer.cancel();
        }
        refresh();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }

    // ---------- manual keys ----------
    /** A key press while a phrase is still being recognized keeps what is shown and restarts listening. */
    private void settle() {
        if (!composing) return;
        endComposing();
        if (want && recognizer != null) {
            recognizer.cancel();
            handler.removeCallbacks(relisten);
            handler.postDelayed(relisten, 150);
        }
    }

    private void typeKey(String text) {
        settle();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void enter() {
        settle();
        InputConnection ic = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if (ic == null) return;
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED;
        if (noAction) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        else ic.performEditorAction(action);
    }

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            handler.postDelayed(this, 60);
        }
    };

    private boolean onBackspaceTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            v.setPressed(true);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            settle();
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            handler.postDelayed(repeatDelete, 400);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            v.setPressed(false);
            handler.removeCallbacks(repeatDelete);
        }
        return true;
    }

    private void switchKeyboard() {
        if (Build.VERSION.SDK_INT >= 28 && switchToPreviousInputMethod()) return;
        picker();
    }

    private void picker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    private void toggleLang() {
        bn = !bn;
        prefs.edit().putString("lang", bn ? "bn" : "en").apply();
        if (want && recognizer != null) {
            endComposing();
            recognizer.cancel();
            handler.removeCallbacks(relisten);
            handler.postDelayed(relisten, 150);
        }
        refresh();
    }

    // ---------- speech ----------
    private void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Allow the microphone in the Amirus Dictator app");
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("No speech service. Install or enable the Google app.");
            return;
        }
        want = true;
        emptyRounds = 0;
        errorRounds = 0;
        listen();
    }

    private void stop() {
        want = false;
        handler.removeCallbacks(relisten);
        if (recognizer != null) recognizer.stopListening();   // still delivers the phrase in progress
        refresh();
    }

    private void halt(String message) {
        want = false;
        handler.removeCallbacks(relisten);
        if (recognizer != null) recognizer.cancel();
        refresh();
        setStatus(message);
    }

    private void listen() {
        handler.removeCallbacks(relisten);
        if (!want) return;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        String locale = bn ? prefs.getString("bnLocale", "bn-BD") : prefs.getString("enLocale", "en-US");
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Do not cut a sentence at a short breath.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        if (segmented && prefs.getBoolean("keepListening", true)) {
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
        }
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            halt("Could not start the speech service");
            return;
        }
        refresh();
    }

    /** Throws the recognizer away so the next listen() creates a new one. */
    private void fresh() {
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
    }

    /** Decides what happens after a phrase or a silent round. */
    private void next() {
        if (!want) {
            refresh();
        } else if (!prefs.getBoolean("keepListening", true)) {
            want = false;
            refresh();
        } else if (emptyRounds >= 4) {
            halt("Paused after silence. Tap the mic to continue.");
        } else {
            handler.postDelayed(relisten, 40);
        }
    }

    private static String first(Bundle results) {
        ArrayList<String> list = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0);
    }

    private void endComposing() {
        if (!composing) return;
        composing = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String text = first(partialResults);
        InputConnection ic = getCurrentInputConnection();
        if (text.isEmpty() || ic == null) return;
        String lead = "";
        if (!composing) {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() > 0 && !Character.isWhitespace(before.charAt(0))) lead = " ";
            composing = true;
            leadForPhrase = lead;
        }
        ic.setComposingText(leadForPhrase + text, 1);
    }

    private String leadForPhrase = "";

    @Override
    public void onResults(Bundle results) {
        commit(results);
        errorRounds = 0;
        next();
    }

    /** A finished phrase inside a long session; the session itself keeps listening. */
    @Override
    public void onSegmentResults(Bundle segmentResults) {
        segmentSeen = true;
        commit(segmentResults);
        errorRounds = 0;
    }

    @Override
    public void onEndOfSegmentedSession() {
        endComposing();
        next();
    }

    private void commit(Bundle results) {
        String text = first(results);
        InputConnection ic = getCurrentInputConnection();
        if (text.trim().isEmpty() || ic == null) {
            endComposing();
            emptyRounds++;
        } else {
            emptyRounds = 0;
            ic.beginBatchEdit();
            if (composing) {
                ic.commitText("", 1);
                composing = false;
            }
            CharSequence before = ic.getTextBeforeCursor(80, 0);
            String out = TextFix.process(text, before == null ? "" : before.toString(), bn, prefs);
            if (!out.isEmpty()) ic.commitText(out, 1);
            ic.endBatchEdit();
        }
    }

    @Override
    public void onError(int error) {
        endComposing();
        if (!want) {
            refresh();
            return;
        }
        boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (segmented && !segmentSeen && !silence) {
            // This phone's speech service does not do long sessions: fall back to one phrase at a time.
            segmented = false;
            fresh();
            handler.postDelayed(relisten, 150);
            return;
        }
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                emptyRounds++;
                next();
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
                if (++errorRounds > 5) {
                    halt("The speech service is not responding. Tap the mic to retry.");
                } else {
                    fresh();   // a busy recognizer rarely recovers; a new one starts at once
                    handler.postDelayed(relisten, 150);
                }
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                halt("Microphone permission is missing. Open the Amirus Dictator app.");
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                halt("Can't reach the speech service. Check your internet.");
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                halt("The microphone is busy or unavailable.");
                break;
            default:
                halt("This language isn't available in your phone's speech service.");
        }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        if (want) setStatus(listeningText());
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}
