/* ====================================================================
 * Copyright (c) 2014 Alpha Cephei Inc.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY ALPHA CEPHEI INC. ``AS IS'' AND
 * ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL CARNEGIE MELLON UNIVERSITY
 * NOR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ====================================================================
 */

package ru.cdp.pbcontrol.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import edu.cmu.pocketsphinx.Assets;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Query;
import ru.cdp.pbcontrol.SettingsActivity;

import static android.widget.Toast.makeText;

public class PbcontrolActivity extends Activity implements
        RecognitionListener {

    /* Named searches allow to quickly reconfigure the decoder */
    private static final String KWS_SEARCH = "wakeup";
    /*private static final String FORECAST_SEARCH = "forecast";
    private static final String DIGITS_SEARCH = "digits";
    private static final String PHONE_SEARCH = "phones";*/
    private static final String MENU_SEARCH = "menu";

    /* Keyword we are looking for to activate menu */
    private static final String KEYPHRASE = "пандора привет";

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static String LastPageNum = null;
    private static String SyncVersion = null;
    private SpeechRecognizer recognizer;
    // private HashMap<String, Integer> captions;
    private Server _dataServer = null;
    private Boolean _isNeedToUpdateServer = null;
    SharedPreferences.OnSharedPreferenceChangeListener listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            switch (key) {
                case "server_url_text": {
                    _isNeedToUpdateServer = true;
                }
                break;
            }
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.pb_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.pb_setting:
                startActivity(new Intent(PbcontrolActivity.this, SettingsActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void setGrammar(List<String> words) {
        String gram = "#JSGF V1.0;\n\n" +
                "grammar menu;\n\n" +
                "public <item> = " + String.join(" | ", words);
        recognizer.addGrammarSearch(MENU_SEARCH, gram);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        // Prepare the data for UI
       /* captions = new HashMap<>();
        captions.put(KWS_SEARCH, R.string.kws_caption);
        captions.put(MENU_SEARCH, R.string.menu_caption);
        captions.put(DIGITS_SEARCH, R.string.digits_caption);
        captions.put(PHONE_SEARCH, R.string.phone_caption);
        captions.put(FORECAST_SEARCH, R.string.forecast_caption);*/
        setContentView(R.layout.main);
        ((TextView) findViewById(R.id.caption_text))
                .setText("Инициализация распознования...");

        // Check if user has given permission to record audio
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(listener);

        // Recognizer initialization is a time-consuming and it involves IO,
        // so we execute it in async task
        new SetupTask(this).execute();
        new GetDataTask(this).execute();

        // System.out.println("Start !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    public Server getDataServer() {
        if (_dataServer == null || _isNeedToUpdateServer) {
            _isNeedToUpdateServer = false;
            String srv_def_url = getResources().getString(R.string.pref_default_display_name);
            String srv_url = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("server_url_text", srv_def_url);

            final OkHttpClient okHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();

            Retrofit retrofit = new Retrofit.Builder()
                    .client(okHttpClient)
                    .baseUrl(srv_url) // Адрес сервера
                    .addConverterFactory(GsonConverterFactory.create()) // говорим ретрофиту что для сериализации необходимо использовать GSON
                    .build();
            _dataServer = retrofit.create(Server.class);
        }
        return _dataServer;
    }

    private int getAppNum() {
        String srv_def_url = getResources().getString(R.string.pref_example_list_default);
        String value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("example_list", srv_def_url);
        Integer retVal;
        try {
            retVal = Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            retVal = 0; // or null if that is your preference
        }
        return retVal;
    }

    /*public void onClickButtonLayout(View view) {
    }*/

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                new SetupTask(this).execute();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (recognizer != null) {
            recognizer.cancel();
            recognizer.shutdown();
        }
    }

    /**
     * In partial result we get quick updates about current hypothesis. In
     * keyword spotting mode we can react here, in other modes we need to wait
     * for final result in onResult.
     */
    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (hypothesis == null)
            return;

        String text = hypothesis.getHypstr();
       /* if (text.equals(KEYPHRASE))
            switchSearch(MENU_SEARCH);
        else if (text.equals(DIGITS_SEARCH))
            switchSearch(DIGITS_SEARCH);
        else if (text.equals(PHONE_SEARCH))
            switchSearch(PHONE_SEARCH);
        else if (text.equals(FORECAST_SEARCH))
            switchSearch(FORECAST_SEARCH);
        else*/
        ((TextView) findViewById(R.id.result_text)).setText(text);
    }

    /**
     * This callback is called when we stop the recognizer.
     */
    @Override
    public void onResult(Hypothesis hypothesis) {
        ((TextView) findViewById(R.id.result_text)).setText("");
        if (hypothesis != null) {
            String text = hypothesis.getHypstr();
            makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    /**
     * We stop recognizer here to get a final result
     */
    @Override
    public void onEndOfSpeech() {
        if (!recognizer.getSearchName().equals(KWS_SEARCH))
            switchSearch(KWS_SEARCH);
    }

    private void switchSearch(String searchName) {
        recognizer.stop();

        // If we are not spotting, start listening with timeout (10000 ms or 10 seconds).
        if (searchName.equals(KWS_SEARCH))
            recognizer.startListening(MENU_SEARCH);
        else
            recognizer.startListening(searchName, 10000);

        /*String caption = getResources().getString(captions.get(searchName));
        ((TextView) findViewById(R.id.caption_text)).setText(caption);*/
    }

    private void setupRecognizer(File assetsDir) throws IOException {
        // The recognizer can be configured to perform multiple searches
        // of different kind and switch between them

        recognizer = SpeechRecognizerSetup.defaultSetup()
                .setAcousticModel(new File(assetsDir, "ru-ru"))
                .setDictionary(new File(assetsDir, "ru.dic"))
                .setRawLogDir(assetsDir) // To disable logging of raw audio comment out this call (takes a lot of space on the device)
                .getRecognizer();
        recognizer.addListener(this);

        /* In your application you might not need to add all those searches.
          They are added here for demonstration. You can leave just one.
         */
        // recognizer.addGrammarSearch();
        // Create keyword-activation search.
        recognizer.addKeyphraseSearch(KWS_SEARCH, KEYPHRASE);

        // Create grammar-based search for selection between demos
        /*File menuGrammar = new File(assetsDir, "menu.gram");
        recognizer.addGrammarSearch(MENU_SEARCH, menuGrammar);

        // Create grammar-based search for digit recognition
        File digitsGrammar = new File(assetsDir, "digits.gram");
        recognizer.addGrammarSearch(DIGITS_SEARCH, digitsGrammar);

        // Create language model search
        File languageModel = new File(assetsDir, "weather.dmp");
        recognizer.addNgramSearch(FORECAST_SEARCH, languageModel);

        // Phonetic search
        File phoneticModel = new File(assetsDir, "en-phone.dmp");
        recognizer.addAllphoneSearch(PHONE_SEARCH, phoneticModel);*/
    }

    @Override
    public void onError(Exception error) {
        ((TextView) findViewById(R.id.caption_text)).setText(error.getMessage());
    }

    @Override
    public void onTimeout() {
        switchSearch(KWS_SEARCH);
    }

    public interface Server {
        @GET("/api/getButtons")
        Call<ButtonsResult> getButtons(@Query("appNum") int appNum);

        @GET("/api/pressButton")
        Call<AppResult> pressButton(@Query("appNum") int appNum, @Query("id") String id);
    }

    private static class SetupTask extends AsyncTask<Void, Void, Exception> {
        WeakReference<PbcontrolActivity> actRef;

        SetupTask(PbcontrolActivity activity) {
            this.actRef = new WeakReference<>(activity);
        }

        @Override
        protected Exception doInBackground(Void... params) {
            try {
                Assets assets = new Assets(actRef.get());
                File assetDir = assets.syncAssets();
                actRef.get().setupRecognizer(assetDir);

            } catch (IOException e) {
                return e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Exception result) {
            if (result != null) {
                ((TextView) actRef.get().findViewById(R.id.caption_text))
                        .setText("Failed to init recognizer " + result);
            } else {
                actRef.get().switchSearch(KWS_SEARCH);
            }
        }
    }

    private static class GetDataTask extends AsyncTask<Void, Void, Exception> {
        private static int isLocked = 0;
        WeakReference<PbcontrolActivity> actRef;

        GetDataTask(PbcontrolActivity activity) {
            this.actRef = new WeakReference<>(activity);
        }

        @Override
        protected Exception doInBackground(Void... params) {

            while (true) {
                try {
                    if (isLocked == 0) {
                        isLocked = 1;

                        Call<ButtonsResult> call = actRef.get().getDataServer()
                                .getButtons(actRef.get().getAppNum());

                        final LinearLayout ll = (LinearLayout) actRef.get().findViewById(R.id.buttonlayout);
                        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        final Context cntx = actRef.get().getApplicationContext();

                        call.enqueue(new Callback<ButtonsResult>() {
                            @Override
                            public void onResponse(Call<ButtonsResult> call, Response<ButtonsResult> response) {

                                if (response.isSuccessful() && response.body() != null) {
                                    ButtonsResult body = response.body();

                                    if (!body.pageNum.equals(LastPageNum) || !body.version.equals(SyncVersion)) {
                                        LastPageNum = body.pageNum;
                                        SyncVersion = body.version;

                                        ll.removeAllViews();
                                        if (body.buttons != null) {
                                            List<String> words = new ArrayList<String>();

                                            for (ButtonItem btn : body.buttons) {
                                                Button myButton = new Button(cntx);
                                                words.add(btn.name.toLowerCase());
                                                myButton.setText(btn.name);
                                                myButton.setTag(R.id.tag_guid, btn.id);
                                                myButton.setOnClickListener(new View.OnClickListener() {
                                                    public void onClick(View view) {
                                                        enableButtons(false);
                                                        Server service = actRef.get().getDataServer();
                                                        Call<AppResult> callPress = service.pressButton(actRef.get().getAppNum(),
                                                                view.getTag(R.id.tag_guid).toString());
                                                        callPress.enqueue(new Callback<AppResult>() {
                                                            @Override
                                                            public void onResponse(Call<AppResult> call, Response<AppResult> response) {
                                                            }

                                                            @Override
                                                            public void onFailure(Call<AppResult> call, Throwable t) {
                                                            }
                                                        });
                                                    }
                                                });

                                                actRef.get().setGrammar(words);
                                                ll.addView(myButton, lp);
                                                //((TextView) actRef.get().findViewById(R.id.caption_text)).setText(btn.name);
                                            }
                                        }
                                    }
                                    enableButtons(body.isBlocked == 0);
                                    // запрос выполнился успешно, сервер вернул Status 200
                                } else {

                                    // сервер вернул ошибку
                                }
                                isLocked = 0;
                            }

                            @Override
                            public void onFailure(Call<ButtonsResult> call, Throwable t) {
                                String urlHost = call != null ? call.request().url().host() : "";
                                //ll.findViewById()
                                TextView myTxt = (TextView) ll.findViewById(R.id.tag_host);
                                if (myTxt == null) { //!el_var.getTag(R.id.tag_host).equals(urlHost)) {

                                    ll.removeAllViews();
                                    myTxt = new Button(cntx);
                                    myTxt.setId(R.id.tag_host);
                                    myTxt.setTextColor(Color.RED);
                                    ll.addView(myTxt, lp);
                                }
                                myTxt.setText("Ошибка запроса к сереру " + urlHost);
                                isLocked = 0;
                                // ошибка во время выполнения запроса
                            }
                        });
                    }
                    Thread.sleep(700);

                } catch (Exception e) {

                    //e.printStackTrace();
                    isLocked = 0;
                }
            }
            //return null;
        }

        @Override
        protected void onPostExecute(Exception result) {
            if (result != null) {
            } else {
            }
        }

        protected void enableButtons(Boolean isEnabled) {
            LinearLayout ll = (LinearLayout) actRef.get().findViewById(R.id.buttonlayout);
            int count = ll.getChildCount();
            for (int i = 0; i < count; i++) {
                View view = ll.getChildAt(i);
                if (view instanceof Button) {
                    Button btn = (Button) view;
                    btn.setEnabled(isEnabled);
                }
            }
        }
    }

    public class AppResult {
        @SerializedName("isBlocked")
        public int isBlocked;
    }

    public class ButtonItem {
        @SerializedName("id")
        public String id;
        @SerializedName("name")
        public String name;
    }

    public class ButtonsResult {
        @SerializedName("version")
        public String version;
        @SerializedName("pageNum")
        public String pageNum;
        @SerializedName("isBlocked")
        public int isBlocked;
        @SerializedName("buttons")
        public List<ButtonItem> buttons;
    }
}