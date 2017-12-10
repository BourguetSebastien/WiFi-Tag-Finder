package wifi.px504.android.sebastienbourguet.fr.testwfi;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static java.lang.String.format;

/**
 * @author Sebastien Bourguet
 * @version 1.2
 *
 * Application
 */
public class MainActivity extends AppCompatActivity {
    private static final int PERIOD = 4_000; // Average time between two scans
    private static final int MAX_LEVEL = 20; // Max value to evaluate signal force received
    private static final int TIME_DISPLAYED = 30_000; // Time displayed in historical graph (in ms)
    private static final int NB_DISPLAYED_POINTS = TIME_DISPLAYED/PERIOD;
    // WIFI useful constants
    private static final int BAND_2GHZ = 1;
    private static final int BAND_5GHZ = 2;
    private static final Integer[] CHANNELS_2GHZ = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    private static final Integer[] CHANNELS_5GHZ = {36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140};
    private static final String[] WIDTHS = {"20 MHz", "40 MHz", "80 MHz", "160 MHz", "Other"};
    // PERMISSIONS
    private static final String[] REQUIRED_PERMISSIONS =  {Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.ACCESS_COARSE_LOCATION};
    private static final int ACCESS_WIFI = 0x1234; // User defined
    private static final int CHANGE_WIFI = 0X5678; // User defined
    private static final int ACCESS_LOCATION = 0x9101; // User defined
    private static final int CODES[] = {ACCESS_WIFI, CHANGE_WIFI, ACCESS_LOCATION};

    // UI
    private Spinner selector;
    private TextView name, frequency, security, labLevel;
    private ImageView securityIcon, activityIndicator;
    private ProgressBar level;
    private GraphView channelGraph, levelGraph;
    private Chronometer chronometer;

    // GRAPH
    private LineGraphSeries<DataPoint> channelSeries;
    private LineGraphSeries<DataPoint> levelSeries;
    private DataPoint points[];
    // SPINNER
    private ArrayAdapter<String> adapter;

    // Wi-Fi
    private WifiManager wifi;
    private WifiReceiver wifiReceiver;
    private int selectedBand = BAND_2GHZ;
    private boolean wifiEnabled = false;
    private String selectedBSSID = "";
    private long timeStart;

    // FILE
    final private SimpleDateFormat format = new SimpleDateFormat(getString(R.string.FILE_NAME_PATTERN));
    OutputStream outputStream;

    /**
     * Method called when app is launched
     * Init Wi-Fi, check if permissions are granted, and prepare UI.
     * @param savedInstanceState param not used
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initWifi();

        makeUI();

        wifiEnabled = checkPermissions(); // Check if permissions are granted
        if (!wifiEnabled) { // Need to ask for permission
            getPermission();
            wifiEnabled = checkPermissions();
        }
        if (wifiEnabled) { // Permissions granted launch scanning
            initFile();
            wifi.startScan();
        }
        else { // Permission not granted, functionality not working
            Toast.makeText(this, "Permissions are not granted, scan not launched", Toast.LENGTH_LONG);
        }
    }

    /**
     * Init UI
     * Prepare all displayed elements
     */
    private void makeUI() {
        /* Get UI elements */
        this.selector = findViewById(R.id.selector);
        this.name = findViewById(R.id.name);
        this.frequency = findViewById(R.id.frequency);
        this.security = findViewById(R.id.security);
        this.securityIcon = findViewById(R.id.securityIcon);
        this.level = findViewById(R.id.level);
        this.labLevel = findViewById(R.id.labLevel);
        this.channelGraph = findViewById(R.id.channelGrpah);
        this.levelGraph = findViewById(R.id.levelGraph);
        this.activityIndicator = findViewById(R.id.activityIndicator);
        this.chronometer = findViewById(R.id.chronometer);

        /* Adapter use to fill and display information in the spinner */
        this.adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        this.selector.setAdapter(this.adapter);
        this.selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String[] s = ((String) parent.getSelectedItem()).split("[()]"); // Keep BSSID information, contained between '()'
                selectedBSSID = s[1];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedBSSID = "";
            }
        });

        /* Init graph */
        this.levelSeries = new LineGraphSeries<>();
        this.levelSeries.setAnimated(true);
        this.levelSeries.setDrawBackground(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.i("APP", "Setting colors");
            this.levelSeries.setColor(getColor(R.color.spartanCrimson));
            this.levelSeries.setBackgroundColor(getColor(R.color.spartanCrimson));
        }
        this.channelGraph.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.VERTICAL);
        this.levelGraph.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.NONE);
        this.levelGraph.addSeries(this.levelSeries);
        initChannelGraph(this.selectedBand);

        this.channelGraph.getGridLabelRenderer().setHighlightZeroLines(true);
        this.channelGraph.getViewport().setYAxisBoundsManual(true);
        this.channelGraph.getViewport().setMinY(-100);
        this.channelGraph.getViewport().setMaxY(1);

        this.levelGraph.getGridLabelRenderer().setNumVerticalLabels(4);

        /* AXIS LEGENDS - NOT USED, TOO BIG FOR A SMALL SCREEN */
//        this.channelGraph.getGridLabelRenderer().setVerticalAxisTitle("Signal level in dB");
//        this.channelGraph.getGridLabelRenderer().setHorizontalAxisTitle("Channel");

//        this.levelGraph.getGridLabelRenderer().setVerticalAxisTitle("dB");
//        this.levelGraph.getGridLabelRenderer().setHorizontalAxisTitle("Time");

        this.level.setMax(MAX_LEVEL);

        /* Animate activity indicator (blink) */
        Animation animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        animation.setDuration(500);
        animation.setRepeatCount(Animation.INFINITE);
        animation.setRepeatMode(Animation.REVERSE);
        activityIndicator.startAnimation(animation);

        this.timeStart = System.currentTimeMillis();
    }

    /**
     * Prepare series used in Channel graph
     * Set length of Collection with number of channel of WiFi Band
     * @param band selected WiFi band
     */
    private void initChannelGraph(int band) {
        switch (band) {
            case BAND_2GHZ:
                points = new DataPoint[CHANNELS_2GHZ.length];
                for (int i = 0; i < CHANNELS_2GHZ.length; i++) {
                    points[i] = new DataPoint(CHANNELS_2GHZ[i], 0);
                }
                break;
            case BAND_5GHZ:
                points = new DataPoint[CHANNELS_5GHZ.length];
                for (int i = 0; i < CHANNELS_5GHZ.length; i++) {
                    points[i] = new DataPoint(CHANNELS_5GHZ[i], 0);
                }
                break;
            default:
                return;
        }
        this.channelSeries = new LineGraphSeries<>(points);
        this.channelSeries.setAnimated(true);
        this.channelGraph.getViewport().setXAxisBoundsManual(true);
        this.channelGraph.getViewport().setMinX(this.channelSeries.getLowestValueX());
        this.channelGraph.getViewport().setMaxX(this.channelSeries.getHighestValueX());
        this.channelGraph.addSeries(this.channelSeries);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.channelSeries.setColor(getColor(R.color.spartanCrimson));
        }
    }

    /**
     * Prepare receiver for WiFi SCAN Finished action
     * Retrieve WiFi manager.
     */
    private void initWifi() {
        this.wifiReceiver = new WifiReceiver();
        registerReceiver(this.wifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        this.wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    }

    /**
     * Update displayed infomartion with selected Scan
     * @param ac Scan displayed
     */
    private void updateUi(ScanResult ac) {
        if (ac == null) return;
        chronometer.stop();
        int channel = convertFrequencyToChannel(ac.frequency);
        this.name.setText(format("%s (%s)", ac.SSID, ac.BSSID));
        this.frequency.setText(Html.fromHtml(format("<b>CH %d</b> - F:%d <i>(width: %s)</i>", channel, ac.frequency, WIDTHS[ac.channelWidth])));
        this.security.setText(ac.capabilities);
        this.level.setProgress(WifiManager.calculateSignalLevel(ac.level, MAX_LEVEL));
        this.labLevel.setText(format("%d dB", ac.level));
        if(ac.capabilities.contains("WPA") || ac.capabilities.contains("WPA2") || ac.capabilities.contains("WEP")) {
            this.securityIcon.setImageResource(android.R.drawable.ic_secure);
        } else {
            this.securityIcon.setImageResource(android.R.drawable.ic_partial_secure);
        }
        this.levelSeries.appendData(new DataPoint((double)((System.currentTimeMillis()-this.timeStart)/1_000), ac.level), false, NB_DISPLAYED_POINTS);
//        this.levelSeries.appendData(new DataPoint(lastIndex, ac.level), false, NB_DISPLAYED_POINTS);
        if (ac.frequency > 4_000 && selectedBand == BAND_2GHZ) { // You need to change conf to handle and display 5GHz
            selectedBand = BAND_5GHZ;
            initChannelGraph(selectedBand);
        }
        else if (ac.frequency < 4_000 && selectedBand == BAND_5GHZ) { // You need to change conf to handle and display 2.4GHz
            selectedBand = BAND_2GHZ;
            initChannelGraph(selectedBand);
        }
        int index = indexOf(selectedBand == BAND_2GHZ ? CHANNELS_2GHZ : CHANNELS_5GHZ, channel);
        points[index] = new DataPoint(channel, ac.level);
        this.channelSeries.resetData(points);
        String separator = getString(R.string.separator);
        String line = format("%s (%s)%s%l%s%d%s%d%s%d", ac.SSID, ac.BSSID, separator, System.currentTimeMillis(), separator, ac.level, separator, channel, separator, ac.frequency);
        try {
            this.outputStream.write(line.getBytes());
        } catch (IOException e) {
            Log.e("APP_FileSave", "Error when trying to write in a file");
        }
        chronometer.setBase(SystemClock.elapsedRealtime());
        chronometer.start();
    }

    /**
     * Create and prepare file used to save received data
     * @return False if file creation failed
     */
    private boolean initFile() {
        String fileName = format.format(new Date(System.currentTimeMillis()));
        File file = new File(getFilesDir(), fileName);
        try {
            outputStream = openFileOutput(fileName, MODE_PRIVATE);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Check if need permission are GRANTED
     * @return True if needed permissions are granted
     */
    private boolean checkPermissions() {
        int access = 0;
        for (String perm : REQUIRED_PERMISSIONS) {
            access = ContextCompat.checkSelfPermission(this, perm);
        }
        return access == PackageManager.PERMISSION_GRANTED* REQUIRED_PERMISSIONS.length;
    }

    /**
     * Aske for needed permission
     */
    private void getPermission() {
        int i =  0;
        for (final String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                // Should we show an explanation?
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                    // Show an explanation to the user *asynchronously* -- don't block
                    // this thread waiting for the user's response! After the user
                    // sees the explanation, try again to request the permission.
                    AlertDialog.Builder dialog = new AlertDialog.Builder(getApplicationContext());
                    dialog.setTitle("Permission required");
                    dialog.setMessage("This application need access to "+perm+".");
                    dialog.setCancelable(true);
                    final int finalI = i;
                    dialog.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{perm}, CODES[finalI]);
                        }
                    });
                } else {
                    // No explanation needed, we can request the permission.
                    ActivityCompat.requestPermissions(this, new String[]{perm}, CODES[i]);
                    // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                    // app-defined int constant. The callback method gets the
                    // result of the request.
                }
            }
            i ++;
        }
    }

    /**
     * Handle action called when user respond to permission ask
     * Check if code correspond to requiered permission and set flag value
     * @param requestCode User defined permission's code
     * @param permissions Permission's name
     * @param grantResults User response (0 if PERMISSION GRANTED)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case ACCESS_WIFI:
            case CHANGE_WIFI:
            case ACCESS_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    this.wifiEnabled = true;
                } else {
                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    this.wifiEnabled = false;
                }
                return;
            }
            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    protected class WifiReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            wifi.startScan();
            List<ScanResult> results = wifi.getScanResults();

            if (results.size() != adapter.getCount()) {
                adapter.clear();
                for (ScanResult sc : results) {
                    adapter.add(sc.SSID + " (" + sc.BSSID + ")");
                }
                adapter.notifyDataSetChanged();
            }
            if (!selectedBSSID.isEmpty()) {
                updateUi(findAP(results, selectedBSSID));
            }
        }
    }

    /**
     * Find @{@link ScanResult} in given @{@link List} with corresponding BSSID name
     * @param results @{@link List} of @{@link ScanResult} where to find element
     * @param bssid BSSID name of wanted @{@link ScanResult}
     * @return Corresponding @{@link ScanResult}
     */
    private ScanResult findAP(List<ScanResult> results, String bssid) {
        for (ScanResult r : results) {
            if (r.BSSID.equals(bssid)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Return channel number for given frequency
     * @param freq frequency of signal
     * @return corresponding channel
     */
    private static int convertFrequencyToChannel(int freq) {
        int resp=0;
        if (freq >= 2412 && freq <= 2484) {
            resp=  (freq - 2412) / 5 + 1;
        } else if (freq >= 5170 && freq <= 5825) {
            resp= (freq - 5170) / 5 + 34;
        }
        return resp;
    }

    /**
     * Find index of element in given @{@link java.util.Collection}
     * @param arr {@link java.util.Collection} Collection where to find element
     * @param val {@link Object} Element to find
     * @param <T> Type of element to find
     * @return index of given element (-1 if element not find)
     */
    private static <T> int indexOf(T[] arr, T val) {
        return Arrays.asList(arr).indexOf(val);
    }
}