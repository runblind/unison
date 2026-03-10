// Copyright 2010 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.android.stardroid.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.FragmentManager;
import android.app.SearchManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.stardroid.ApplicationConstants;
import com.google.android.stardroid.R;
import com.google.android.stardroid.activities.dialogs.EulaDialogFragment;
import com.google.android.stardroid.activities.dialogs.HelpDialogFragment;
import com.google.android.stardroid.activities.dialogs.MultipleSearchResultsDialogFragment;
import com.google.android.stardroid.activities.dialogs.NoSearchResultsDialogFragment;
import com.google.android.stardroid.activities.dialogs.NoSensorsDialogFragment;
import com.google.android.stardroid.activities.dialogs.TimeTravelDialogFragment;
import com.google.android.stardroid.activities.util.ActivityLightLevelChanger;
import com.google.android.stardroid.activities.util.ActivityLightLevelChanger.NightModeable;
import com.google.android.stardroid.activities.util.ActivityLightLevelManager;
import com.google.android.stardroid.activities.util.FullscreenControlsManager;
import com.google.android.stardroid.activities.util.GooglePlayServicesChecker;
import com.google.android.stardroid.base.Lists;
import com.google.android.stardroid.control.AstronomerModel;
import com.google.android.stardroid.control.AstronomerModel.Pointing;
import com.google.android.stardroid.control.ControllerGroup;
import com.google.android.stardroid.control.MagneticDeclinationCalculatorSwitcher;
import com.google.android.stardroid.inject.HasComponent;
import com.google.android.stardroid.layers.LayerManager;
import com.google.android.stardroid.math.CoordinateManipulationsKt;
import com.google.android.stardroid.math.MathUtils;
import com.google.android.stardroid.math.Vector3;
import com.google.android.stardroid.renderer.RendererController;
import com.google.android.stardroid.renderer.SkyRenderer;
import com.google.android.stardroid.search.SearchResult;
import com.google.android.stardroid.touch.DragRotateZoomGestureDetector;
import com.google.android.stardroid.touch.GestureInterpreter;
import com.google.android.stardroid.touch.MapMover;
import com.google.android.stardroid.util.Analytics;
import com.google.android.stardroid.util.AnalyticsInterface;
import com.google.android.stardroid.util.MiscUtil;
import com.google.android.stardroid.util.SensorAccuracyMonitor;
import com.example.soundlib.Bluetooth;
import com.google.android.stardroid.views.ButtonLayerView;
import com.google.android.stardroid.views.PreferencesButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * The main map-rendering Activity.
 */
public class DynamicStarMapActivity extends InjectableActivity
        implements OnSharedPreferenceChangeListener, HasComponent<DynamicStarMapComponent>, TextToSpeech.OnInitListener {
  private static final int TIME_DISPLAY_DELAY_MILLIS = 1000;

  private Runnable runnable;

  private boolean onPause = false;

  private Thread threadToSetBluetoothDirectionView;

  private TextToSpeech tts;
  private Boolean startingInfos = true;
  public Boolean balayage = false;
  public String balayageObject;
  private boolean searching = false;
  private boolean targetFound = true;

  private boolean azimuthFound = true;
  private boolean zoomActive = false;

  public boolean inTargetDirection = false;
  public String target;

  private Bluetooth bluetooth = new Bluetooth();

  private AudioManager audioManager;
  private int maxVolume;
  private static String[] PERMISSIONS_LOCATION = {
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
          Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS,
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.BLUETOOTH_PRIVILEGED
  };


  SkyRenderer renderer;
  private FullscreenControlsManager fullscreenControlsManager;

  @Override
  public DynamicStarMapComponent getComponent() {
    return daggerComponent;
  }

  /**
   * Passed to the renderer to get per-frame updates from the model.
   *
   * @author John Taylor
   */
  private static final class RendererModelUpdateClosure implements Runnable {
    private RendererController rendererController;
    private AstronomerModel model;
    private boolean viewDirectionMode;

    public RendererModelUpdateClosure(AstronomerModel model,
                                      RendererController rendererController, SharedPreferences sharedPreferences) {
      this.model = model;
      this.rendererController = rendererController;
      // TODO(jontayler): figure out why we need to do this here.
      updateViewDirectionMode(model, sharedPreferences);
    }

    @Override
    public void run() {
      Pointing pointing = model.getPointing();

      //device direction
      float directionX = pointing.getLineOfSightX();
      float directionY = pointing.getLineOfSightY();
      float directionZ = pointing.getLineOfSightZ();

      //Log.e("direction", directionX+","+directionY+","+directionZ);

      float upX = pointing.getPerpendicularX();
      float upY = pointing.getPerpendicularY();
      float upZ = pointing.getPerpendicularZ();

      rendererController.queueSetViewOrientation(directionX, directionY, directionZ, upX, upY, upZ);

      Vector3 up = model.getPhoneUpDirection();
      rendererController.queueTextAngle(MathUtils.atan2(up.x, up.y));
      rendererController.queueViewerUpDirection(model.getZenith().copyForJ());

      float fieldOfView = model.getFieldOfView();
      rendererController.queueFieldOfView(fieldOfView);
    }
  }

  private static void updateViewDirectionMode(AstronomerModel model, SharedPreferences sharedPreferences) {
    String viewDirectionMode =
            sharedPreferences.getString(ApplicationConstants.VIEW_MODE_PREFKEY, "STANDARD");
    switch (viewDirectionMode) {
      case "ROTATE90":
        model.setViewDirectionMode(AstronomerModel.ViewDirectionMode.ROTATE90);
        break;
      case "TELESCOPE":
        model.setViewDirectionMode(AstronomerModel.ViewDirectionMode.TELESCOPE);
        break;
      default:
        model.setViewDirectionMode(AstronomerModel.ViewDirectionMode.STANDARD);
    }
  }

  // Activity for result Ids
  public static final int GOOGLE_PLAY_SERVICES_REQUEST_CODE = 1;
  public static final int GOOGLE_PLAY_SERVICES_REQUEST_LOCATION_PERMISSION_CODE = 2;
  // End Activity for result Ids

  private static final float ROTATION_SPEED = 10;
  private static final String TAG = MiscUtil.getTag(DynamicStarMapActivity.class);

  private ImageButton cancelSearchButton;
  @Inject
  ControllerGroup controller;
  private GestureDetector gestureDetector;
  @Inject
  AstronomerModel model;
  private RendererController rendererController;
  private boolean nightMode = false;
  private boolean searchMode = false;
  private Vector3 searchTarget = CoordinateManipulationsKt.getGeocentricCoords(0, 0);

  @Inject
  SharedPreferences sharedPreferences;
  private GLSurfaceView skyView;
  private PowerManager.WakeLock wakeLock;
  private String searchTargetName;
  @Inject
  LayerManager layerManager;
  // TODO(widdows): Figure out if we should break out the
  // time dialog and time player into separate activities.
  private View timePlayerUI;
  private DynamicStarMapComponent daggerComponent;
  @Inject
  @Named("timetravel")
  Provider<MediaPlayer> timeTravelNoiseProvider;
  @Inject
  @Named("timetravelback")
  Provider<MediaPlayer> timeTravelBackNoiseProvider;
  private MediaPlayer timeTravelNoise;
  private MediaPlayer timeTravelBackNoise;
  @Inject
  Handler handler;
  @Inject
  Analytics analytics;
  @Inject
  GooglePlayServicesChecker playServicesChecker;
  @Inject
  FragmentManager fragmentManager;
  @Inject
  EulaDialogFragment eulaDialogFragmentNoButtons;
  @Inject
  TimeTravelDialogFragment timeTravelDialogFragment;
  @Inject
  HelpDialogFragment helpDialogFragment;
  @Inject
  NoSearchResultsDialogFragment noSearchResultsDialogFragment;
  @Inject
  MultipleSearchResultsDialogFragment multipleSearchResultsDialogFragment;
  @Inject
  NoSensorsDialogFragment noSensorsDialogFragment;
  @Inject
  SensorAccuracyMonitor sensorAccuracyMonitor;
  // A list of runnables to post on the handler when we resume.
  private List<Runnable> onResumeRunnables = new ArrayList<>();

  // We need to maintain references to these objects to keep them from
  // getting gc'd.
  @SuppressWarnings("unused")
  @Inject
  MagneticDeclinationCalculatorSwitcher magneticSwitcher;

  private DragRotateZoomGestureDetector dragZoomRotateDetector;
  @Inject
  Animation flashAnimation;
  private ActivityLightLevelManager activityLightLevelManager;
  private long sessionStartTime;

  static {
    // Chargement de la bibliothèque native
    System.loadLibrary("soundlib");
  }

  private SpeechRecognizer speechRecognizer;
  private Intent recognizerIntent;

  private Context context = this;

  private native void initializeClasses(String filePath);

  private native void startProcessing();

  private native void stopProcessing();

  private native void releaseClasses();

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onCreate(Bundle icicle) {
    Log.d(TAG, "onCreate at " + System.currentTimeMillis());
    super.onCreate(icicle);

    checkPermissions();

    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

    daggerComponent = DaggerDynamicStarMapComponent.builder()
            .applicationComponent(getApplicationComponent())
            .dynamicStarMapModule(new DynamicStarMapModule(this)).build();
    daggerComponent.inject(this);

    sharedPreferences.registerOnSharedPreferenceChangeListener(this);

    // Set up full screen mode, hide the system UI etc.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

    // TODO(jontayler): upgrade to
    // getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    // when we reach API level 16.
    // http://developer.android.com/training/system-ui/immersive.html for the right way
    // to do it at API level 19.
    //getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

    // Eventually we should check at the point of use, but this will do for now.  If the
    // user revokes the permission later then odd things may happen.
    playServicesChecker.maybeCheckForGooglePlayServices();

    initializeModelViewController();
    checkForSensorsAndMaybeWarn();

    // Search related
    setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

    ActivityLightLevelChanger activityLightLevelChanger = new ActivityLightLevelChanger(this,
            new NightModeable() {
              @Override
              public void setNightMode(boolean nightMode1) {
                DynamicStarMapActivity.this.rendererController.queueNightVisionMode(nightMode1);
              }
            });
    activityLightLevelManager = new ActivityLightLevelManager(activityLightLevelChanger,
            sharedPreferences);

    PowerManager pm = ContextCompat.getSystemService(this, PowerManager.class);
    if (pm != null) {
      wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, TAG);
    }

    // Were we started as the result of a search?
    Intent intent = getIntent();
    Log.d(TAG, "Intent received: " + intent);
    if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
      Log.d(TAG, "Started as a result of a search");
      doSearchWithIntent(intent);
    }
    Log.d(TAG, "-onCreate at " + System.currentTimeMillis());

    //registerReceiver(bluetooth.getBluetoothReceiver(), new IntentFilter(BluetoothDevice.ACTION_FOUND));
    //bluetooth.bluetoothConnexion();
    /*if (connectionState == BluetoothProfile.STATE_CONNECTED) {
      bluetooth.deviceConnected();
      bluetooth.connectToDevice();
    }*/
    //loadSongFile("p01_4ch.wav");
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
    speechRecognizer.setRecognitionListener(new RecognitionListener() {
      @Override
      public void onBeginningOfSpeech() {
        // La reconnaissance vocale a commencé
        //Toast.makeText(context, "start speak recognition", Toast.LENGTH_SHORT).show();
      }

      @Override
      public void onRmsChanged(float rmsdB) {
      }

      @Override
      public void onBufferReceived(byte[] buffer) {
      }

      @Override
      public void onEndOfSpeech() {
        // La reconnaissance vocale a pris fin
        //restart();
      }

      @Override
      public void onReadyForSpeech(Bundle params) {
        // Le système est prêt à écouter
      }

      @Override
      public void onResults(Bundle results) {
        // Résultats de la reconnaissance vocale
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
          //Log.e("test", matches.toString());
          String spokenText = matches.get(0);
          getActionFromText(spokenText);
        }
      }

      @Override
      public void onPartialResults(Bundle partialResults) {
      }

      @Override
      public void onEvent(int eventType, Bundle params) {
      }

      @Override
      public void onError(int error) {
        // Gérer les erreurs de reconnaissance vocale
        //restart();
      }
    });

// Initialiser l'intent de reconnaissance vocale
    recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR"); // Changer pour la langue souhaitée

    setRendererStarsObjectsAndButton();

    /*Thread starsObjects = new Thread(){
      @Override
      public void run() {
        setRendererStarsObjectsAndButton();
      }
    };*/
    Thread checkCloseObjects = new Thread() {
      @Override
      public void run() {
        renderer.closeObjectsWithPosition();
      }
    };
    //starsObjects.start();
    new Handler(Looper.getMainLooper()).postDelayed(checkCloseObjects::start, 2000);

    tts = new TextToSpeech(this, this);
    renderer.setOverlayBluetooth(bluetooth);
  }

  @Override
  public void onInit(int status) {
    if (status == TextToSpeech.SUCCESS) {
      // Choisir la langue
      int result = tts.setLanguage(Locale.FRANCE);

      if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        // La langue choisie n'est pas supportée
        System.out.println("La langue choisie n'est pas supportée.");
      } else {
        tts.setSpeechRate(1.5f);//tts speed voice
        infoToSpeakOnStart(5);
        renderer.setTts(tts);
        new Handler(Looper.getMainLooper()).postDelayed(this::setVoiceOnStart, 2000);
      }
    } else {
      System.out.println("Initialisation du TextToSpeech échouée.");
    }
  }

  private void speakOut(String text) {
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    setVoiceToSpeech(renderer.stars_voice);
  }

  private void startOboeSound() {
    Log.e("start", "start");
    startProcessing();
  }

  private void stopOboeSound() {
    Log.e("stop", "stop");
    stopProcessing();
  }

  private void startSongForSearch(Vector3 coords) {
    bluetooth.setThetaM(new float[]{coords.x, 0f, 0f, 0f});
    bluetooth.setPhiM(new float[]{coords.y, 0f, 0f, 0f});
    bluetooth.receiveData(true);
    threadToSetBluetoothDirectionView = new Thread() {
      @Override
      public void run() {
        super.run();
        setBluetootViewDirection();
      }
    };
    threadToSetBluetoothDirectionView.start();
  }

  private void setBluetootViewDirection() {
    bluetooth.setCurrentPositionX(renderer.viewDirectionX);
    bluetooth.setCurrentPositionY(renderer.viewDirectionY);
    bluetooth.setCurrentPositionZ(renderer.viewDirectionZ);
    if (bluetooth.getTargetFound() && targetFound) {
      speakOut("Vous êtes face à " + target);
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        targetFound = true;
        inTargetDirection = false;
      }, 4000);
      targetFound = false;
      inTargetDirection = true;
    }
    else if(bluetooth.getAzimuthFound() && azimuthFound){
      if(bluetooth.getLookUp()){
        speakOut("Haut");
      }
      else if(bluetooth.getLookDown()){
        speakOut("Bas");
      }
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        azimuthFound = true;
      }, 6000);
      azimuthFound = false;
    }
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      if (searching || zoomActive) {
        setBluetootViewDirection();
      }
    }, 1);
  }

  private void infoToSpeakOnStart(int value) {
    if (value > 0) {
      if (!tts.isSpeaking() && startingInfos) {
        if (value == 5) {
          speakOut("Cette voix annonce les étoiles");
        } else if (value == 4) {
          setVoiceToSpeech(renderer.constellations_voice);
          speakOut("Celle-ci les constellations");
        } else if (value == 3) {
          setVoiceToSpeech(renderer.solarSystem_voice);
          speakOut("Et celle la le système solaire");
        } else if (value == 2) {
          setVoiceToSpeech(renderer.stars_voice);
          speakOut("Pour démarrer la reconnaissance vocale, appuyez sur le bouton volume bas");
        } else {
          speakOut("Pour accéder au tuto, dites tuto une fois la reconnaissance vocale activée");
        }
        value--;
      }
      int finalValue = value;
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        infoToSpeakOnStart(finalValue);
      }, 500);
    }
  }

  private void setVoiceToSpeech(String text) {
    Set<Voice> voices = tts.getVoices();
    for (Voice voice : voices) {
      if (voice.getName().equals(text)) {
        tts.setVoice(voice);
      }
    }
  }

  public ArrayList<Boolean> getButtonsState() {
    ArrayList<Boolean> statesButtons = new ArrayList<Boolean>();
    PreferencesButton etoiles = findViewById(R.id.etoiles);
    PreferencesButton constellations = findViewById(R.id.constellations);
    PreferencesButton solarSystem = findViewById(R.id.solarSystem);
    statesButtons.add(etoiles.getIsOn());
    statesButtons.add(constellations.getIsOn());
    statesButtons.add(solarSystem.getIsOn());
    return statesButtons;
  }

  public void setRendererStarsObjectsAndButton() {
    //objects in sky
    ArrayList<ArrayList<SearchResult>> result = layerManager.getSearchIndexs(); // Remplacez par le type correct si ce n'est pas une liste de chaînes
    renderer.setStarsObject(result);
    Log.e("skyObjects", result.toString());

    //buttons state
    ArrayList<Boolean> statesButtons = getButtonsState();
    renderer.setButtonStates(statesButtons);
    Log.e("buttonStates", statesButtons.toString());
    //new Handler(Looper.getMainLooper()).postDelayed(this::setRendererStarsObjectsAndButton, 1000);
  }

  public static String copyRawWavToCacheDirAndGetPath(Context context, String filename) {
    String rawFileName = filename.split("\\.")[0];

    Resources res = context.getResources();
    int resId = res.getIdentifier(rawFileName, "raw", context.getPackageName());
    // Ouvre le fichier WAV depuis le dossier raw
    InputStream inputStream = res.openRawResource(resId);

    // Crée un fichier dans externalCacheDir pour écrire les données
    File outputFile = new File(context.getExternalCacheDir(), filename);
    FileOutputStream outputStream;
    try {
      outputStream = new FileOutputStream(outputFile);

      // Buffer pour lire et écrire les données
      byte[] buffer = new byte[1024];
      int bytesRead;

      // Lit les données depuis le fichier WAV et écrit dans le fichier de sortie
      while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
      }

      // Ferme les flux de lecture et d'écriture
      inputStream.close();
      outputStream.close();

      // Renvoie le chemin d'accès au fichier .wav copié
      return outputFile.getAbsolutePath();
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private void startListening() {
    // Démarrer la reconnaissance vocale
    speechRecognizer.startListening(recognizerIntent);
  }

  private void checkPermissionAndStartListening() {

    // Vérifier la permission d'enregistrement audio
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
      } else {
        startListening();
      }
    } else {
      startListening();
    }
  }

  private void checkForSensorsAndMaybeWarn() {
    SensorManager sensorManager = ContextCompat.getSystemService(this, SensorManager.class);
    if (sensorManager != null && sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            && sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null) {
      Log.i(TAG, "Minimum sensors present");
      // We want to reset to auto mode on every restart, as users seem to get
      // stuck in manual mode and can't find their way out.
      // TODO(johntaylor): this is a bit of an abuse of the prefs system, but
      // the button we use is wired into the preferences system.  Should probably
      // change this to a use a different mechanism.
      sharedPreferences.edit().putBoolean(ApplicationConstants.AUTO_MODE_PREF_KEY, true).apply();
      setAutoMode(true);
      return;
    }
    // Missing at least one sensor.  Warn the user.
    handler.post(new Runnable() {
      @Override
      public void run() {
        if (!sharedPreferences
                .getBoolean(ApplicationConstants.NO_WARN_ABOUT_MISSING_SENSORS, false)) {
          Log.d(TAG, "showing no sensor dialog");
          noSensorsDialogFragment.show(fragmentManager, "No sensors dialog");
          // First time, force manual mode.
          sharedPreferences.edit().putBoolean(ApplicationConstants.AUTO_MODE_PREF_KEY, false)
                  .apply();
          setAutoMode(false);
        } else {
          Log.d(TAG, "showing no sensor toast");
          Toast.makeText(
                  DynamicStarMapActivity.this, R.string.no_sensor_warning, Toast.LENGTH_LONG).show();
          // Don't force manual mode second time through - leave it up to the user.
        }
      }
    });
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Trigger the initial hide() shortly after the activity has been
    // created, to briefly hint to the user that UI controls
    // are available.
    if (fullscreenControlsManager != null) {
      fullscreenControlsManager.flashTheControls();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    MenuInflater inflater = getMenuInflater();
    inflater.inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "DynamicStarMap onDestroy");
    if (tts != null) {
      tts.stop();
      tts.shutdown();
    }
    super.onDestroy();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    switch (keyCode) {
      case (KeyEvent.KEYCODE_DPAD_LEFT):
        Log.d(TAG, "Key left");
        controller.rotate(-10.0f);
        break;
      case (KeyEvent.KEYCODE_DPAD_RIGHT):
        Log.d(TAG, "Key right");
        controller.rotate(10.0f);
        break;
      case (KeyEvent.KEYCODE_VOLUME_DOWN):
        if (balayage) {
          balayage = false;
        } else if (searchMode) {
          cancelSearch();
        }
        else if(zoomActive){
          bluetooth.setSoundOrientationThread(false);
          stopOboeSound();
          zoomActive = false;
          threadToSetBluetoothDirectionView.interrupt();
        }
        else if (startingInfos && tts.isSpeaking()) {
          tts.stop();
          startingInfos = false;
        } else if (tts.isSpeaking()) {
          tts.stop();
        }
        checkPermissionAndStartListening();
        break;
      case (KeyEvent.KEYCODE_BACK):
        // If we're in search mode when the user presses 'back' the natural
        // thing is to back out of search.
        Log.d(TAG, "In search mode " + searchMode);
        if (searchMode) {
          cancelSearch();
          break;
        }
      default:
        Log.d(TAG, "Key: " + event);
        return super.onKeyDown(keyCode, event);
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    fullscreenControlsManager.delayHideTheControls();
    Bundle menuEventBundle = new Bundle();
    switch (item.getItemId()) {
      case R.id.menu_item_search:
        Log.d(TAG, "Search");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.SEARCH_REQUESTED_LABEL);
        onSearchRequested();
        break;
      case R.id.menu_item_settings:
        Log.d(TAG, "Settings");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.SETTINGS_OPENED_LABEL);
        startActivity(new Intent(this, EditSettingsActivity.class));
        break;
      case R.id.menu_item_help:
        Log.d(TAG, "Help");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.HELP_OPENED_LABEL);
        helpDialogFragment.show(fragmentManager, "Help Dialog");
        break;
      case R.id.menu_item_dim:
        Log.d(TAG, "Toggling nightmode");
        nightMode = !nightMode;
        sharedPreferences.edit().putString(ActivityLightLevelManager.LIGHT_MODE_KEY,
                nightMode ? "NIGHT" : "DAY").commit();
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.TOGGLED_NIGHT_MODE_LABEL);
        break;
      case R.id.menu_item_time:
        Log.d(TAG, "Starting Time Dialog from menu");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.TIME_TRAVEL_OPENED_LABEL);
        if (!timePlayerUI.isShown()) {
          Log.d(TAG, "Resetting time in time travel dialog.");
          controller.goTimeTravel(new Date());
        } else {
          Log.d(TAG, "Resuming current time travel dialog.");
        }
        timeTravelDialogFragment.show(fragmentManager, "Time Travel");
        break;
      case R.id.menu_item_gallery:
        Log.d(TAG, "Loading gallery");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.GALLERY_OPENED_LABEL);
        startActivity(new Intent(this, ImageGalleryActivity.class));
        break;
      case R.id.menu_item_tos:
        Log.d(TAG, "Loading ToS");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.TOS_OPENED_LABEL);
        eulaDialogFragmentNoButtons.show(fragmentManager, "Eula Dialog No Buttons");
        break;
      case R.id.menu_item_calibrate:
        Log.d(TAG, "Loading Calibration");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.CALIBRATION_OPENED_LABEL);
        Intent intent = new Intent(this, CompassCalibrationActivity.class);
        intent.putExtra(CompassCalibrationActivity.HIDE_CHECKBOX, true);
        startActivity(intent);
        break;
      case R.id.menu_item_diagnostics:
        Log.d(TAG, "Loading Diagnostics");
        menuEventBundle.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.DIAGNOSTICS_OPENED_LABEL);
        startActivity(new Intent(this, DiagnosticActivity.class));
        break;
      default:
        Log.e(TAG, "Unwired-up menu item");
        return false;
    }
    analytics.trackEvent(Analytics.MENU_ITEM_EVENT, menuEventBundle);
    return true;
  }

  @Override
  public void onStart() {
    super.onStart();
    sessionStartTime = System.currentTimeMillis();
  }

  private enum SessionBucketLength {
    LESS_THAN_TEN_SECS(10), TEN_SECS_TO_THIRTY_SECS(30),
    THIRTY_SECS_TO_ONE_MIN(60), ONE_MIN_TO_FIVE_MINS(300),
    MORE_THAN_FIVE_MINS(Integer.MAX_VALUE);
    private int seconds;

    SessionBucketLength(int seconds) {
      this.seconds = seconds;
    }
  }

  private SessionBucketLength getSessionLengthBucket(int sessionLengthSeconds) {
    for (SessionBucketLength bucket : SessionBucketLength.values()) {
      if (sessionLengthSeconds < bucket.seconds) {
        return bucket;
      }
    }
    Log.e(TAG, "Programming error - should not get here");
    return SessionBucketLength.MORE_THAN_FIVE_MINS;
  }

  @Override
  public void onStop() {
    super.onStop();
    Log.e("stop", "stio");
    // Define a session as being the time between the main activity being in
    // the foreground and pushed back.  Note that this will mean that sessions
    // do get interrupted by (e.g.) loading preference or help screens.
    int sessionLengthSeconds = (int) ((
            System.currentTimeMillis() - sessionStartTime) / 1000);
    SessionBucketLength bucket = getSessionLengthBucket(sessionLengthSeconds);
    Bundle b = new Bundle();
    // Let's see how well Analytics buckets things and log the raw number
    b.putInt(Analytics.SESSION_LENGTH_TIME_VALUE, sessionLengthSeconds);
    analytics.trackEvent(Analytics.SESSION_LENGTH_EVENT, b);

    //unregisterReceiver(bluetooth.getBluetoothReceiver());
    speakOut("Mise en pause");
    onPause = true;
  }

  @Override
  public void onResume() {
    Log.d(TAG, "onResume at " + System.currentTimeMillis());
    super.onResume();
    Log.i(TAG, "Resuming");
    timeTravelNoise = timeTravelNoiseProvider.get();
    timeTravelBackNoise = timeTravelBackNoiseProvider.get();

    wakeLock.acquire();
    Log.i(TAG, "Starting view");
    skyView.onResume();
    Log.i(TAG, "Starting controller");
    controller.start();
    activityLightLevelManager.onResume();
    if (controller.isAutoMode()) {
      sensorAccuracyMonitor.start();
    }
    for (Runnable runnable : onResumeRunnables) {
      handler.post(runnable);
    }
    if(onPause){
      speakOut("reprise");
      onPause = false;
    }
    Log.d(TAG, "-onResume at " + System.currentTimeMillis());
    //registerReceiver(bluetooth.getBluetoothReceiver(), new IntentFilter(BluetoothDevice.ACTION_FOUND));
  }

  public void setTimeTravelMode(Date newTime) {
    SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy.MM.dd G  HH:mm:ss z");
    Toast.makeText(this,
            String.format(getString(R.string.time_travel_start_message_alt),
                    dateFormatter.format(newTime)),
            Toast.LENGTH_LONG).show();
    if (sharedPreferences.getBoolean(ApplicationConstants.SOUND_EFFECTS, true)) {
      try {
        timeTravelNoise.start();
      } catch (IllegalStateException | NullPointerException e) {
        Log.e(TAG, "Exception trying to play time travel sound", e);
        // It's not the end of the world - carry on.
      }
    }

    Log.d(TAG, "Showing TimePlayer UI.");
    timePlayerUI.setVisibility(View.VISIBLE);
    timePlayerUI.requestFocus();
    flashTheScreen();
    controller.goTimeTravel(newTime);
  }

  public void setNormalTimeModel() {
    if (sharedPreferences.getBoolean(ApplicationConstants.SOUND_EFFECTS, true)) {
      try {
        timeTravelBackNoise.start();
      } catch (IllegalStateException | NullPointerException e) {
        Log.e(TAG, "Exception trying to play return time travel sound", e);
        // It's not the end of the world - carry on.
      }
    }
    flashTheScreen();
    controller.useRealTime();
    Toast.makeText(this,
            R.string.time_travel_close_message,
            Toast.LENGTH_SHORT).show();
    Log.d(TAG, "Leaving Time Travel mode.");
    timePlayerUI.setVisibility(View.GONE);
  }

  private void flashTheScreen() {
    final View view = findViewById(R.id.view_mask);
    // We don't need to set it invisible again - the end of the
    // animation will see to that.
    // TODO(johntaylor): check if setting it to GONE will bring
    // performance benefits.
    view.setVisibility(View.VISIBLE);
    view.startAnimation(flashAnimation);
  }

  @Override
  public void onPause() {
    Log.d(TAG, "DynamicStarMap onPause");
    super.onPause();
    sensorAccuracyMonitor.stop();
    if (timeTravelNoise != null) {
      timeTravelNoise.release();
      timeTravelNoise = null;
    }
    if (timeTravelBackNoise != null) {
      timeTravelBackNoise.release();
      timeTravelBackNoise = null;
    }
    for (Runnable runnable : onResumeRunnables) {
      handler.removeCallbacks(runnable);
    }
    activityLightLevelManager.onPause();
    controller.stop();
    skyView.onPause();
    wakeLock.release();
    // Debug.stopMethodTracing();
    Log.d(TAG, "DynamicStarMap -onPause");
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    Log.d(TAG, "Preferences changed: key=" + key);
    if (key == null) {
      return;
    }
    switch (key) {
      case ApplicationConstants.AUTO_MODE_PREF_KEY:
        boolean autoMode = sharedPreferences.getBoolean(key, true);
        Log.d(TAG, "Automode is set to " + autoMode);
        if (!autoMode) {
          Log.d(TAG, "Switching to manual control");
          Toast.makeText(DynamicStarMapActivity.this, R.string.set_manual, Toast.LENGTH_SHORT).show();
        } else {
          Log.d(TAG, "Switching to sensor control");
          Toast.makeText(DynamicStarMapActivity.this, R.string.set_auto, Toast.LENGTH_SHORT).show();
        }
        setAutoMode(autoMode);
        break;
      case ApplicationConstants.VIEW_MODE_PREFKEY:
        updateViewDirectionMode(model, sharedPreferences);
      default:
        return;
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    //Log.d(TAG, "Touch event " + event);
    // Either of the following detectors can absorb the event, but one
    // must not hide it from the other
    boolean eventAbsorbed = false;
    if (gestureDetector.onTouchEvent(event)) {
      eventAbsorbed = true;
    }
    if (dragZoomRotateDetector.onTouchEvent(event)) {
      eventAbsorbed = true;
    }
    return eventAbsorbed;
  }

  @Override
  public boolean onTrackballEvent(MotionEvent event) {
    // Log.d(TAG, "Trackball motion " + event);
    controller.rotate(event.getX() * ROTATION_SPEED);
    return true;
  }

  private void doSearchWithIntent(Intent searchIntent) {
    // If we're already in search mode, cancel it.
    if (searchMode) {
      cancelSearch();
    }
    Log.d(TAG, "Performing Search");
    final String queryString = searchIntent.getStringExtra(SearchManager.QUERY);
    searchMode = true;
    Log.d(TAG, "Query string " + queryString);
    List<SearchResult> results = layerManager.searchByObjectName(queryString);
    Bundle b = new Bundle();
    b.putString(AnalyticsInterface.SEARCH_TERM, queryString);
    b.putBoolean(AnalyticsInterface.SEARCH_SUCCESS, results.size() > 0);
    analytics.trackEvent(AnalyticsInterface.SEARCH_EVENT, b);
    if (results.isEmpty()) {
      Log.d(TAG, "No results returned");
      noSearchResultsDialogFragment.show(fragmentManager, "No Search Results");
    } else if (results.size() > 1) {
      Log.d(TAG, "Multiple results returned");
      showUserChooseResultDialog(results);
    } else {
      Log.d(TAG, "One result returned.");
      final SearchResult result = results.get(0);
      activateSearchTarget(result.coords(), result.getCapitalizedName());
    }
  }

  private void showUserChooseResultDialog(List<SearchResult> results) {
    multipleSearchResultsDialogFragment.clearResults();
    for (SearchResult result : results) {
      multipleSearchResultsDialogFragment.add(result);
    }
    multipleSearchResultsDialogFragment.show(fragmentManager, "Multiple Search Results");
  }

  private void initializeModelViewController() {
    Log.i(TAG, "Initializing Model, View and Controller @ " + System.currentTimeMillis());
    setContentView(R.layout.skyrenderer);
    skyView = (GLSurfaceView) findViewById(R.id.skyrenderer_view);
    // We don't want a depth buffer.
    skyView.setEGLConfigChooser(false);
    renderer = new SkyRenderer(getResources());
    skyView.setRenderer(renderer);


    rendererController = new RendererController(renderer, skyView);
    // The renderer will now call back every frame to get model updates.
    rendererController.addUpdateClosure(
            new RendererModelUpdateClosure(model, rendererController, sharedPreferences));

    Log.i(TAG, "Setting layers @ " + System.currentTimeMillis());
    layerManager.registerWithRenderer(rendererController);
    Log.i(TAG, "Set up controllers @ " + System.currentTimeMillis());
    controller.setModel(model);
    controller.zoomBy(90);
    wireUpScreenControls(); // TODO(johntaylor) move these?
    wireUpTimePlayer();  // TODO(widdows) move these?
  }

  private void setAutoMode(boolean auto) {
    Bundle b = new Bundle();
    b.putString(Analytics.MENU_ITEM_EVENT_VALUE, Analytics.TOGGLED_MANUAL_MODE_LABEL);
    controller.setAutoMode(auto);
    if (auto) {
      sensorAccuracyMonitor.start();
    } else {
      sensorAccuracyMonitor.stop();
    }
  }

  private void wireUpScreenControls() {
    cancelSearchButton = (ImageButton) findViewById(R.id.cancel_search_button);
    // TODO(johntaylor): move to set this in the XML once we don't support 1.5
    cancelSearchButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        cancelSearch();
      }
    });

    ButtonLayerView providerButtons = (ButtonLayerView) findViewById(R.id.layer_buttons_control);

    int numChildren = providerButtons.getChildCount();
    List<View> buttonViews = new ArrayList<>();
    for (int i = 0; i < numChildren; ++i) {
      ImageButton button = (ImageButton) providerButtons.getChildAt(i);
      buttonViews.add(button);
    }
    buttonViews.add(findViewById(R.id.manual_auto_toggle));
    ButtonLayerView manualButtonLayer = (ButtonLayerView) findViewById(
            R.id.layer_manual_auto_toggle);

    fullscreenControlsManager = new FullscreenControlsManager(
            this,
            findViewById(R.id.main_sky_view),
            Lists.<View>asList(manualButtonLayer, providerButtons),
            buttonViews);

    MapMover mapMover = new MapMover(model, controller, this);

    gestureDetector = new GestureDetector(this, new GestureInterpreter(
            fullscreenControlsManager, mapMover, renderer, this));
    dragZoomRotateDetector = new DragRotateZoomGestureDetector(mapMover);
  }

  public void cancelSearch() {
    View searchControlBar = findViewById(R.id.search_control_bar);
    searchControlBar.setVisibility(View.INVISIBLE);
    rendererController.queueDisableSearchOverlay();
    searchMode = false;
    if (searching) {
      bluetooth.setSoundOrientationThread(false);
      stopOboeSound();
      threadToSetBluetoothDirectionView.interrupt();
      searching = false;
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Log.d(TAG, "New Intent received " + intent);
    if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
      doSearchWithIntent(intent);
    }
  }

  @Override
  protected void onRestoreInstanceState(Bundle icicle) {
    Log.d(TAG, "DynamicStarMap onRestoreInstanceState");
    super.onRestoreInstanceState(icicle);
    if (icicle == null) return;
    searchMode = icicle.getBoolean(ApplicationConstants.BUNDLE_SEARCH_MODE);
    float x = icicle.getFloat(ApplicationConstants.BUNDLE_X_TARGET);
    float y = icicle.getFloat(ApplicationConstants.BUNDLE_Y_TARGET);
    float z = icicle.getFloat(ApplicationConstants.BUNDLE_Z_TARGET);
    searchTarget = new Vector3(x, y, z);
    searchTargetName = icicle.getString(ApplicationConstants.BUNDLE_TARGET_NAME);
    if (searchMode) {
      Log.d(TAG, "Searching for target " + searchTargetName + " at target=" + searchTarget);
      rendererController.queueEnableSearchOverlay(searchTarget, searchTargetName);
      cancelSearchButton.setVisibility(View.VISIBLE);
    }
    nightMode = icicle.getBoolean(ApplicationConstants.BUNDLE_NIGHT_MODE, false);
  }

  @Override
  protected void onSaveInstanceState(Bundle icicle) {
    Log.d(TAG, "DynamicStarMap onSaveInstanceState");
    icicle.putBoolean(ApplicationConstants.BUNDLE_SEARCH_MODE, searchMode);
    icicle.putFloat(ApplicationConstants.BUNDLE_X_TARGET, searchTarget.x);
    icicle.putFloat(ApplicationConstants.BUNDLE_Y_TARGET, searchTarget.y);
    icicle.putFloat(ApplicationConstants.BUNDLE_Z_TARGET, searchTarget.z);
    icicle.putString(ApplicationConstants.BUNDLE_TARGET_NAME, searchTargetName);
    icicle.putBoolean(ApplicationConstants.BUNDLE_NIGHT_MODE, nightMode);
    super.onSaveInstanceState(icicle);
  }

  public void activateSearchTarget(Vector3 target, final String searchTerm) {
    Log.d(TAG, "Item " + searchTerm + " selected");
    // Store these for later.
    Log.e("target", "" + target);
    searchTarget = target;
    searchTargetName = searchTerm;
    Log.d(TAG, "Searching for target=" + target);
    rendererController.queueViewerUpDirection(model.getZenith().copyForJ());
    rendererController.queueEnableSearchOverlay(target.copyForJ(), searchTerm);
    boolean autoMode = sharedPreferences.getBoolean(ApplicationConstants.AUTO_MODE_PREF_KEY, true);
    if (!autoMode) {
      controller.teleport(target);
    }

    TextView searchPromptText = (TextView) findViewById(R.id.search_status_label);
    searchPromptText.setText(
            String.format("%s %s", getString(R.string.search_target_looking_message), searchTerm));
    View searchControlBar = findViewById(R.id.search_control_bar);
    searchControlBar.setVisibility(View.VISIBLE);
    //if(bluetooth.deviceIsConnected()){
    startOboeSound();
    searching = true;
    startSongForSearch(target);
    //}
  }

  /**
   * Creates and wire up all time player controls.
   */
  private void wireUpTimePlayer() {
    Log.d(TAG, "Initializing TimePlayer UI.");
    timePlayerUI = findViewById(R.id.time_player_view);
    ImageButton timePlayerCancelButton = (ImageButton) findViewById(R.id.time_player_close);
    ImageButton timePlayerBackwardsButton = (ImageButton) findViewById(
            R.id.time_player_play_backwards);
    ImageButton timePlayerStopButton = (ImageButton) findViewById(R.id.time_player_play_stop);
    ImageButton timePlayerForwardsButton = (ImageButton) findViewById(
            R.id.time_player_play_forwards);
    final TextView timeTravelSpeedLabel = (TextView) findViewById(R.id.time_travel_speed_label);

    timePlayerCancelButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Log.d(TAG, "Heard time player close click.");
        setNormalTimeModel();
      }
    });
    timePlayerBackwardsButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Log.d(TAG, "Heard time player play backwards click.");
        controller.decelerateTimeTravel();
        timeTravelSpeedLabel.setText(controller.getCurrentSpeedTag());
      }
    });
    timePlayerStopButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Log.d(TAG, "Heard time player play stop click.");
        controller.pauseTime();
        timeTravelSpeedLabel.setText(controller.getCurrentSpeedTag());
      }
    });
    timePlayerForwardsButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        Log.d(TAG, "Heard time player play forwards click.");
        controller.accelerateTimeTravel();
        timeTravelSpeedLabel.setText(controller.getCurrentSpeedTag());
      }
    });

    Runnable displayUpdater = new Runnable() {
      private TextView timeTravelTimeReadout = (TextView) findViewById(
              R.id.time_travel_time_readout);
      private TextView timeTravelStatusLabel = (TextView) findViewById(
              R.id.time_travel_status_label);
      private TextView timeTravelSpeedLabel = (TextView) findViewById(
              R.id.time_travel_speed_label);
      private final SimpleDateFormat dateFormatter = new SimpleDateFormat(
              "yyyy.MM.dd G  HH:mm:ss z");
      private Date date = new Date();

      @Override
      public void run() {
        long time = model.getTimeMillis();
        date.setTime(time);
        timeTravelTimeReadout.setText(dateFormatter.format(date));
        if (time > System.currentTimeMillis()) {
          timeTravelStatusLabel.setText(R.string.time_travel_label_future);
        } else {
          timeTravelStatusLabel.setText(R.string.time_travel_label_past);
        }
        timeTravelSpeedLabel.setText(controller.getCurrentSpeedTag());
        handler.postDelayed(this, TIME_DISPLAY_DELAY_MILLIS);
      }
    };
    onResumeRunnables.add(displayUpdater);
  }

  public AstronomerModel getModel() {
    return model;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == GOOGLE_PLAY_SERVICES_REQUEST_CODE) {
      playServicesChecker.runAfterDialog();
      return;
    }
    Log.w(TAG, "Unhandled activity result");
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         String[] permissions,
                                         int[] grantResults) {
    if (requestCode == GOOGLE_PLAY_SERVICES_REQUEST_LOCATION_PERMISSION_CODE) {
      playServicesChecker.runAfterPermissionsCheck(requestCode, permissions, grantResults);
      return;
    }
    Log.w(TAG, "Unhandled request permissions result");
  }

  private void checkPermissions() {
    int permission2 = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN);
    if (permission2 != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
              this,
              PERMISSIONS_LOCATION,
              1
      );
    }
  }

  private void getActionFromText(String spokenText) {
    String text = spokenText.toLowerCase();
    String[] list = text.split(" ");
    int lenght = list[0].length();
    Toast.makeText(this, spokenText, Toast.LENGTH_SHORT).show();
    int effect = getEffectNumberFromText(text);
    Log.e("effect", ""+effect);
    switch (effect) {
      case -1:
        speakOut("Je n'ai pas compris");
        break;
      case 0:
        tuto(text);
        break;
      case 1:
        balayage = true;
        balayage(renderer.closeObjects);
        break;
      case 2:
        if (text.length() > 9) {
          String searchObjectName = getSkyObjectName(text.substring(lenght), "recherche");
          List<SearchResult> resultsOfSearch = layerManager.searchByObjectName(searchObjectName);
          if (resultsOfSearch.size() == 1) {
            loadSongForSearch(searchObjectName);
            target = searchObjectName;
            activateSearchTarget(resultsOfSearch.get(0).coords(), resultsOfSearch.get(0).getCapitalizedName());
            searchMode = true;
          } else {
            speakOut("Élément non visible");
          }
        } else {
          speakOut("Veuillez donner le nom d'un astre");
        }
        break;
      case 3:
        if (text.length() > 5) {
          String selectObjectName = getSkyObjectName(text.substring(lenght), "zoom");
          String[] constellation_selected = renderer.readCSV(this, selectObjectName, "select_constellations.csv");
          String[] tab = constellation_selected[1].split(";");
          ArrayList<String> stringList = new ArrayList<>(Arrays.asList(tab));
          Log.e("constellation zoom", stringList.toString());
          zoomOnConstellation(stringList);
          break;
        }else {
          speakOut("Veuillez donner le nom d'un astre");
        }

      case 4:
        String infoObjectname = getSkyObjectName(text.substring(lenght), "info");
        Log.e("infos", infoObjectname);
        if (Objects.equals(infoObjectname, "")) {
          speakOut("Je n'ai pas compris votre demande");
        } else {
          getInfoAboutObject(infoObjectname);
        }
        break;
      case 5:
        if ((text.contains("débutant") || text.contains("facile")) && !renderer.getModeEasyVoice()) {
          renderer.setModeEasyVoice(true);
          speakOut("mode débutant activé");
        } else if (text.contains("avancé") && renderer.getModeEasyVoice()) {
          renderer.setModeEasyVoice(false);
          speakOut("mode avancé activé");
        }
        break;
      case 6:
        String[] values = text.split(" ");
        if (values.length < 4) {
          int valueVolume;
          if (Objects.equals(values[1], "moins") || Objects.equals(values[1], "-")) {
            valueVolume = Integer.parseInt(values[2]) * -1;
          } else {
            valueVolume = Integer.parseInt(values[1]);
          }
          if (valueVolume < 0 || valueVolume > 100) {
            speakOut("Le volume doit etre entre 0 et 100");
          } else {
            setVolume(valueVolume);
            speakOut("Volume mis à " + valueVolume + "%");
          }
        } else {
          speakOut("Pour modifier le volume, dites volume suivi du pourcentage voulue");
        }
        break;
      case 7:
        setButtonsSkyObjects(true, false, false);
        setVoiceToSpeech(renderer.constellations_voice);
        speakOut("constellations visibles");
        setRendererStarsObjectsAndButton();
        break;
      case 8:
        setButtonsSkyObjects(false, true, false);
        setVoiceToSpeech(renderer.stars_voice);
        speakOut("étoiles visibles");
        setRendererStarsObjectsAndButton();
        break;
      case 9:
        setButtonsSkyObjects(false, false, true);
        setVoiceToSpeech(renderer.solarSystem_voice);
        speakOut("système solaire visible");
        setRendererStarsObjectsAndButton();
        break;
      case 10:
        setButtonsSkyObjects(true, true, true);
        setVoiceToSpeech(renderer.stars_voice);
        speakOut("ciel complet visible");
        setRendererStarsObjectsAndButton();
        break;
      case 11:
        if(text.contains("normal") || text.contains("normale")){
          tts.setSpeechRate(1.2f);
          speakOut("vitesse de lecture normale");
        }
        else if(text.contains("rapide")){
          tts.setSpeechRate(1.5f);
          speakOut("vitesse de lecture rapide");
        }
        break;
    }
  }

  private int getEffectNumberFromText(String spokenText){
    String[] sentence = spokenText.split(" ");
    for(String word : sentence){
      String[] value = renderer.readCSV(this, word, "keyword.csv");
      if(value[0] != null){
         return Integer.parseInt(value[0]);
      }
    }
    return -1;
  }

  private void setVolume(int percentage) {
    int newVolume = (int) ((percentage / 100.0) * maxVolume);
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, AudioManager.FLAG_SHOW_UI);
    Log.d("Volume", "Nouveau volume: " + newVolume + " / " + maxVolume);
  }

  private void setButtonsSkyObjects(boolean constellations, boolean etoiles, boolean solarSystem){
    PreferencesButton constellationButton = findViewById(R.id.constellations);
    PreferencesButton starsButton = findViewById(R.id.etoiles);
    PreferencesButton solarSystemButton = findViewById(R.id.solarSystem);
    if(constellationButton.getIsOn() != constellations){
      constellationButton.performClick();
    }
    if(starsButton.getIsOn() != etoiles){
      starsButton.performClick();
    }
    if(solarSystemButton.getIsOn() != solarSystem){
      solarSystemButton.performClick();
    }
  }
  
  private void setVoiceOnStart(){
    PreferencesButton constellationButton = findViewById(R.id.constellations);
    PreferencesButton starsButton = findViewById(R.id.etoiles);
    PreferencesButton solarSystemButton = findViewById(R.id.solarSystem);
    if(constellationButton.getIsOn() && !starsButton.getIsOn()){
      setVoiceToSpeech(renderer.constellations_voice);
    }
    else if(solarSystemButton.getIsOn() && !constellationButton.getIsOn()){
      setVoiceToSpeech(renderer.solarSystem_voice);
    }
    else{
      setVoiceToSpeech(renderer.stars_voice);
    }
  }

  public void getInfoAboutObject(String objectName){
    setVoiceToSpeech(renderer.stars_voice);
    //renderer.checkCSV(context, skyObjectName);
    renderer.checkCSV(context, objectName);
  }

  private void tuto(String text){
    if(text.contains("astre proche")){
      speakOut("La fonction astre proche permet de lister les 5 éléments les plus proches du centre du champ de vision. Pour lancez cette fonction, faites un double tap sur l'écran");
    }
    else if(text.contains("recherche")){
      speakOut("La fonction de recherche permet de chercher un astre dans le ciel. Elle va lancer une musique qui sera jouée dans la direction de l'astre souhaité. Pour lancer le mode recherche, lancez la reconnaissance vocale et dites recherche puis le nom de l'objet voulu");
    }
    else if(text.contains("zoom")){
      speakOut("La fonction zoom permet de zoomer sur une certaine constellation. ELle permet de jouer un son par étoiles dans la constellation, chaque son ayant sa propre orientation et étant plus ou moins audible selon la proximité de l'étoile par rapport au centre du champ de vision." +
              " Attention, seulement certaines constellations sont accessibles avec ce mode. Pour lancer le mode zoom, lancez la reconnaissance vocale et dites zoom puis le nom de l'objet voulu");
    }
    else if(text.contains("info")){
      speakOut("La fonction info permet d'obtenir des informations sur l'objet voulu. Pour lancer le mode infos, lancez la reconnaissance vocale et dites infos puis le nom de l'objet voulu");
    }
    else if(text.contains("affichage")){
      speakOut("La fonction affichage permet d'afficher certains astres. Pour afficher le ciel complet, dites ciel complet, sinon pour afficher un type d'astres, lancez la reconnaissance vocale et dites le type d'astres que vous voulez voir entre constellations, étoiles et système solaire");
    }
    else if(text.contains("mode")){
      speakOut("La fonction mode permet de modifier la façon dont sont annoncés les astres à proximités. Le mode facile annonce le type d'astres qui seront annoncés ensuite avec la voix qui correspond tandis que le mode avancé ne laisse que le changement de voix. Poir changer de mode, ma,cez la reconnaissance vocale et dites mode puis avancé ou facile selon le mode voulu");
    }
    else if(text.contains("balayage")){
      speakOut("La fonction balayage permet d'annoncer les 5 astres les plus proches lentement en boucle. Vous pouvez obtenir les informations du dernier astre annoncé en faisant un double tap sur l'écran. Pour lancer le mode balayage, lancez la reconnaissance vocale puis dites balayage");
    }
    else if(text.contains("volume")){
      speakOut("La fonction volume permet de modifier le volume. Pour cela, lancez la reconnaissance vocale, puis dites voulme suivie d'une valeur entre 0 et 100");
    }
    else if(text.contains("débit")){
      speakOut("La fonction débit (ou vitesse) permet de modifier la vitesse de lecture. Pour la modifier, après avoir lancé la reconaisscance vocale, dites débit ou vitesse puis normal ou rapide");
    }
    else{
      speakOut("Pour découvrir les fonctionnalités, dites tuto suivi de la fonctionnalité voulue");
      waitWhileTTSIsSpeaking();
      //remettre zoom apres fin démo
      speakOut("Les fonctionnalités sont les suivantes: astre proche. recherche. info. balayage. affichage. mode. volume. débit");
      waitWhileTTSIsSpeaking();
      speakOut("Les fonctionnalités qui concernent les astres peuvent toutes êtres arrêtées avec le bouton volume bas");
    }
  }

  //give the closes objects form view slowly
  private void balayage(ArrayList<SearchResult> objects){
    ArrayList<SearchResult> closeObjects = new ArrayList<>();

    ArrayList<ArrayList<SearchResult>> starsObjects  = renderer.getStarsObjects();
    for(ArrayList<SearchResult> list : starsObjects) {//parcoure le tableau des objets
      for (SearchResult search : objects) {//parcoure le tableau des objets les plus proches
        if (list.contains(search)) {
          closeObjects.add(search);
        }
      }
    }
    speakCloseObjectsOneByOne(0, closeObjects);
  }

  private void speakCloseObjectsOneByOne(int position, ArrayList<SearchResult> objects){
    if(position < objects.size() && balayage) {
      Log.e("balayage", objects.get(position).getCapitalizedName());
      SearchResult currentObject = objects.get(position);
      setBalayageVoice(currentObject);
      speakOut(currentObject.getCapitalizedName());
      balayageObject = objects.get(position).getCapitalizedName();
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
        speakCloseObjectsOneByOne(position+1, objects);
      }, 2000);
    }else{
      if(balayage) {
        balayage(renderer.closeObjects);
      }
    }
  }

  private void setBalayageVoice(SearchResult object){
    ArrayList<ArrayList<SearchResult>> skyObjects = renderer.getStarsObjects();

    PreferencesButton constellationButton = findViewById(R.id.constellations);
    PreferencesButton starsButton = findViewById(R.id.etoiles);
    PreferencesButton solarSystemButton = findViewById(R.id.solarSystem);

    if(constellationButton.getIsOn() && !starsButton.getIsOn()){
      setVoiceToSpeech(renderer.constellations_voice);
    }
    else if(solarSystemButton.getIsOn() && !constellationButton.getIsOn()){
      setVoiceToSpeech(renderer.solarSystem_voice);
    }
    else if(starsButton.getIsOn() && !constellationButton.getIsOn()){
      setVoiceToSpeech(renderer.stars_voice);
    }
    else{
      if(skyObjects.get(0).contains(object)){
        setVoiceToSpeech(renderer.stars_voice);
      }else if(skyObjects.get(1).contains(object)){
        setVoiceToSpeech(renderer.constellations_voice);
      }
      else{
        setVoiceToSpeech(renderer.solarSystem_voice);
      }
    }
  }

  //get the object's name search with vocal with phonetic comparison
  private String getSkyObjectName(String text, String action){
    String objectToSearch = "";
    List<SearchResult> resultsOfSearch;
    if(Objects.equals(action, "info")){
      resultsOfSearch = layerManager.searchByObjectNameForInfos(text);
    }
    else{
      resultsOfSearch = layerManager.searchByObjectName(text);
    }
    //if no object found, search with phonetic matching
    if(resultsOfSearch.isEmpty()) {
      ArrayList<ArrayList<SearchResult>> objects = layerManager.getSearchIndexs();
      for (ArrayList<SearchResult> list : objects) {
        for (SearchResult result : list) {
          int distance = phoneticDistance(text.toLowerCase(), result.getCapitalizedName().toLowerCase());
          if (distance <= 1) {
            return result.getCapitalizedName();
          }
        }
      }
    }
    else{
      objectToSearch = resultsOfSearch.get(0).getCapitalizedName();
    }
    return objectToSearch;
  }


  //return an hashmap from the selected constellation
  private Map<String, float[]> starsOfConstellations(ArrayList<String> constellations){
    Map<String, float[]> starsAzimuth = new HashMap<>();
    for(String str:constellations){
      String[] value = str.split(",");
      starsAzimuth.put(value[0], new float[]{Float.parseFloat(value[1]), Float.parseFloat(value[2])});
    }
    return starsAzimuth;
  }

  //convert word into phonetic String
  public static String convertToPhonetic(String mot) {
    List<Map.Entry<String, String>> correspondances = Arrays.asList(
            new AbstractMap.SimpleEntry<>(" ", ""),
            new AbstractMap.SimpleEntry<>("'", ""),
            new AbstractMap.SimpleEntry<>("-", ""),
            new AbstractMap.SimpleEntry<>("é", "e"),
            new AbstractMap.SimpleEntry<>("ï", "i"),
            new AbstractMap.SimpleEntry<>("eaux", "o"),
            new AbstractMap.SimpleEntry<>("eau", "o"),
            new AbstractMap.SimpleEntry<>("au", "o"),
            new AbstractMap.SimpleEntry<>("eu", "u"),
            new AbstractMap.SimpleEntry<>("ère", "er"),
            new AbstractMap.SimpleEntry<>("ai", "e"),
            new AbstractMap.SimpleEntry<>("ei", "e"),
            new AbstractMap.SimpleEntry<>("er", "e"),
            new AbstractMap.SimpleEntry<>("et", "e"),
            new AbstractMap.SimpleEntry<>("ez", "e"),
            new AbstractMap.SimpleEntry<>("oe", "e"),
            new AbstractMap.SimpleEntry<>("ou", "ou"),
            new AbstractMap.SimpleEntry<>("on", "on"),
            new AbstractMap.SimpleEntry<>("aon", "on"),
            new AbstractMap.SimpleEntry<>("in", "in"),
            new AbstractMap.SimpleEntry<>("ain", "in"),
            new AbstractMap.SimpleEntry<>("gn", "gn"),
            new AbstractMap.SimpleEntry<>("ill", "y"),
            new AbstractMap.SimpleEntry<>("y", "i"),
            new AbstractMap.SimpleEntry<>("sa", "za"),
            new AbstractMap.SimpleEntry<>("gu", "g"),
            new AbstractMap.SimpleEntry<>("qu", "k"),
            new AbstractMap.SimpleEntry<>("ph", "f"),
            new AbstractMap.SimpleEntry<>("ch", "ch"),
            new AbstractMap.SimpleEntry<>("ss", "s"),
            new AbstractMap.SimpleEntry<>("ce", "se"),
            new AbstractMap.SimpleEntry<>("ci", "si"),
            new AbstractMap.SimpleEntry<>("ci", "si"),
            new AbstractMap.SimpleEntry<>("tie", "si"),
            new AbstractMap.SimpleEntry<>("ge", "je"),
            new AbstractMap.SimpleEntry<>("gi", "ji"),
            new AbstractMap.SimpleEntry<>("es", "e"),
            new AbstractMap.SimpleEntry<>("ç", "s"),
            new AbstractMap.SimpleEntry<>("c", "k"),
            new AbstractMap.SimpleEntry<>("q", "k"),
            new AbstractMap.SimpleEntry<>("k", "k"),
            new AbstractMap.SimpleEntry<>("g", "j"),
            new AbstractMap.SimpleEntry<>("ea", "i"),
            new AbstractMap.SimpleEntry<>("x", "ks"),
            new AbstractMap.SimpleEntry<>("h", ""),
            new AbstractMap.SimpleEntry<>("w", "v")
    );

    for (Map.Entry<String, String> entry : correspondances) {
      mot = mot.replace(entry.getKey(), entry.getValue());
    }

    if (mot.endsWith("e") || mot.endsWith("d") || mot.endsWith("s")) {
      mot = mot.substring(0, mot.length() - 1);
    }

    mot = mot.replaceAll("(.)\\1", "$1");

    return mot;
  }

  //return distance between 2 phonectic String
  public static int levenshteinDistance(String s1, String s2) {
    if (s1.length() < s2.length()) {
      return levenshteinDistance(s2, s1);
    }

    if (s2.isEmpty()) {
      return s1.length();
    }

    int[] previousRow = new int[s2.length() + 1];
    for (int i = 0; i < previousRow.length; i++) {
      previousRow[i] = i;
    }

    for (int i = 0; i < s1.length(); i++) {
      int[] currentRow = new int[s2.length() + 1];
      currentRow[0] = i + 1;

      for (int j = 0; j < s2.length(); j++) {
        int insertions = previousRow[j + 1] + 1;
        int deletions = currentRow[j] + 1;
        int substitutions = previousRow[j] + (s1.charAt(i) != s2.charAt(j) ? 1 : 0);
        currentRow[j + 1] = Math.min(Math.min(insertions, deletions), substitutions);
      }

      System.arraycopy(currentRow, 0, previousRow, 0, currentRow.length);
    }

    Log.e("phonetic", s1+"..."+s2+"--"+previousRow[s2.length()]);
    return previousRow[s2.length()];
  }

  //return the phonetic distance between 2 string
  public static int phoneticDistance(String word1, String word2) {
    String phonetic1 = convertToPhonetic(word1);
    String phonetic2 = convertToPhonetic(word2);
    return levenshteinDistance(phonetic1, phonetic2);
  }

  private void loadSongFile(String filename){
    releaseClasses();
    String file = copyRawWavToCacheDirAndGetPath(this, filename);

    initializeClasses(file);
  }

  private void waitWhileTTSIsSpeaking(){
    while(tts.isSpeaking()){
      new Handler(Looper.getMainLooper()).postDelayed(() -> {
      }, 1000);
    }
  }

  private void loadSongForSearch(String skyObjectName){
    if(skyObjectName.equalsIgnoreCase("lune")){
      loadSongFile("moon.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("mars")){
      loadSongFile("mars_def2.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("jupiter")){
      loadSongFile("jupiter.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("mercure")){
      loadSongFile("mercure.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("neptune")){
      loadSongFile("neptune.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("pluton")){
      loadSongFile("pluton.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("saturne")){
      loadSongFile("saturne.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("soleil")){
      loadSongFile("soleil.wav");
    }
    else if(skyObjectName.equalsIgnoreCase("vénus")){
      loadSongFile("venus.wav");
    }
    else{
      loadSongFile("p01_4ch.wav");
    }
  }

  public void zoomOnConstellation(ArrayList<String> constellation){
    float[] theta = new float[4];
    float[] phi = new float[4];
    for(int i = 0; i<4; i++){
      if(constellation.size() <= i){
        break;
      }
      else {
        String[] starInfo = constellation.get(i).split(",");
        theta[i] = Float.parseFloat(starInfo[1]);
        phi[i] = Float.parseFloat(starInfo[2]);
      }
    }
    Log.e("zoomOnConstelation theta", theta[0]+"; "+theta[1]+"; "+theta[2]+"; "+theta[3]);
    Log.e("zoomOnConstelation phi", phi[0]+"; "+phi[1]+"; "+phi[2]+"; "+phi[3]);
    zoomActive = true;
    loadSongFile("test4.wav");
    bluetooth.setThetaM(theta);
    bluetooth.setPhiM(phi);
    bluetooth.receiveData(true);
    threadToSetBluetoothDirectionView = new Thread() {
      @Override
      public void run() {
        super.run();
        setBluetootViewDirection();
      }
    };
    threadToSetBluetoothDirectionView.start();
    startOboeSound();
  }
}
