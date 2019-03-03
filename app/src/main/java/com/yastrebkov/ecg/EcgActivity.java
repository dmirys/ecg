package com.yastrebkov.ecg;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.gson.Gson;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsNotificationListener;
import com.movesense.mds.MdsResponseListener;
import com.movesense.mds.MdsSubscription;

import java.util.ArrayList;
import java.util.List;

public class EcgActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {
    private static final int DEFAULT_SAMPLE_RATE = 125;
    static EcgActivity s_INSTANCE = null;
    private static final String LOG_TAG = EcgActivity.class.getSimpleName();

    public static final String SERIAL = "serial";
    String connectedSerial;

    private LineGraphSeries<DataPoint> mSeriesECG;
    private int mDataPointsAppended = 0;
    private MdsSubscription mECGSubscription;
    private MdsSubscription mHRSubscription;

    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";

    public static final String URI_ECG_INFO = "/Meas/ECG/Info";
    public static final String URI_ECG_ROOT = "/Meas/ECG/";
    public static final String URI_MEAS_HR = "/Meas/HR";


    private AlertDialog mAlertDialog;

    Switch mSwitchECGEnabled;
    Spinner mSpinnerSampleRates;

    private ArrayAdapter<String> mSpinnerAdapter;
    private final List<String> mSpinnerRates = new ArrayList<>();

    private Mds getMDS() {return MainActivity.mMds;}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        s_INSTANCE = this;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ecg_inner_content);

        mSwitchECGEnabled = (Switch)findViewById(R.id.switchECGEnabled);
        mSwitchECGEnabled.setOnCheckedChangeListener(this);

        mSpinnerSampleRates = (Spinner)findViewById(R.id.spinnerSampleRates);
        mSpinnerSampleRates.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {

                ((TextView) parent.getChildAt(0)).setTextColor(Color.WHITE);
                //((TextView) parent.getChildAt(0)).setTextSize(5);

            }

            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Find serial in opening intent
        Intent intent = getIntent();
        connectedSerial = intent.getStringExtra(SERIAL);


        // Set SampleRate mSpinnerSampleRates
        mSpinnerAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, mSpinnerRates);
        mSpinnerSampleRates.setAdapter(mSpinnerAdapter);

        // Set ECG graph
        GraphView graph = (GraphView) findViewById(R.id.ecg_gv);
        mSeriesECG = new LineGraphSeries<DataPoint>();
        graph.addSeries(mSeriesECG);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(500);

        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(-2000 /*-2000*/);
        graph.getViewport().setMaxY(2000 /*2000*/);

        graph.getViewport().setScrollable(false);
        graph.getViewport().setScrollableY(false);

        graph.setTitleColor(Color.WHITE);
        //graph.getGridLabelRenderer().setVerticalLabelsColor(Color.WHITE);
        graph.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        //graph.getGridLabelRenderer().setVerticalLabelsVisible(false);
        graph.getGridLabelRenderer().setHighlightZeroLines(false);
        mSeriesECG.setColor(Color.GREEN);

        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle("Пожалуйста, подождите...")
                .setMessage("Информация загружается...")
                .create();

        // Display dialog
        mAlertDialog.show();

        // Start by getting ECG info
        fetchECGInfo();
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG,"onDestroy()");

        unsubscribeAll();
        EcgActivity.s_INSTANCE = null;

        super.onDestroy();
    }

    private void fetchECGInfo() {
        String uri = SCHEME_PREFIX + connectedSerial + URI_ECG_INFO;

        getMDS().get(uri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "ECG info succesful: " + data);

                // Hide dialog
                mAlertDialog.dismiss();

                EcgInfoResponse infoResponse = new Gson().fromJson(data, EcgInfoResponse.class);

                // Fill sample rates to the spinner
                mSpinnerRates.clear();
                for (int sampleRate : infoResponse.content.availableSampleRates) {
                    mSpinnerRates.add(""+sampleRate);
                }

                mSpinnerAdapter.notifyDataSetChanged();

                mSpinnerSampleRates.setSelection(mSpinnerAdapter.getPosition(""+DEFAULT_SAMPLE_RATE));

                if (mSpinnerSampleRates.getChildCount() > 0) {
                    ((TextView) mSpinnerSampleRates.getChildAt(0)).setTextColor(Color.WHITE);
                }

                // Enable Subscription switch
                mSwitchECGEnabled.setEnabled(true);

                // Subscribe to HR/IBI
                enableHRSubscription();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "ECG info returned error: " + e);

                // Hide dialog
                mAlertDialog.dismiss();
            }
        });

    }

    private void enableHRSubscription() {
        // Make sure there is no subscription
        unsubscribeHR();

        // Build JSON doc that describes what resource and device to subscribe
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_MEAS_HR).append("\"}").toString();
        Log.d(LOG_TAG, strContract);

        mHRSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                strContract, new MdsNotificationListener() {
                    @Override
                    public void onNotification(String data) {
                        Log.d(LOG_TAG, "onNotification(): " + data);

                        HrResponse hrResponse = new Gson().fromJson(
                                data, HrResponse.class);

                        if (hrResponse != null) {
                            int hr = (int)hrResponse.body.average;
                            ((TextView)findViewById(R.id.textViewHR)).setText("" + hr);
                            ((TextView)findViewById(R.id.textViewIBI)).setText(hrResponse.body.rrData.length > 0 ? "" + hrResponse.body.rrData[hrResponse.body.rrData.length-1] : "--");
                        }
                    }

                    @Override
                    public void onError(MdsException error) {
                        Log.e(LOG_TAG, "HRSubscription onError(): ", error);
                        unsubscribeHR();
                    }
                });

    }

    private void enableECGSubscription() {
        // Make sure there is no subscription
        unsubscribeECG();

        // Build JSON doc that describes what resource and device to subscribe
        StringBuilder sb = new StringBuilder();
        int sampleRate = Integer.parseInt(""+mSpinnerSampleRates.getSelectedItem());
        final int GRAPH_WINDOW_WIDTH = sampleRate*3;
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_ECG_ROOT).append(sampleRate).append("\"}").toString();
        Log.d(LOG_TAG, strContract);
        // Clear graph
        mSeriesECG.resetData(new DataPoint[0]);
        final GraphView graph = (GraphView) findViewById(R.id.ecg_gv);
        graph.getViewport().setMaxX(GRAPH_WINDOW_WIDTH);
        mDataPointsAppended = 0;

        mECGSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                strContract, new MdsNotificationListener() {
                    @Override
                    public void onNotification(String data) {
                        Log.d(LOG_TAG, "onNotification(): " + data);

                        EcgResponse ecgResponse = new Gson().fromJson(
                                data, EcgResponse.class);

                        if (ecgResponse != null) {
                            for (int sample : ecgResponse.body.samples) {
                                try {
                                    mSeriesECG.appendData(
                                            new DataPoint(mDataPointsAppended, sample), true,
                                            GRAPH_WINDOW_WIDTH);
                                } catch (IllegalArgumentException e) {
                                    Log.e(LOG_TAG, "GraphView error ", e);
                                }
                                mDataPointsAppended++;
                            }
                        }
                    }

                    @Override
                    public void onError(MdsException error) {
                        Log.e(LOG_TAG, "onError(): ", error);
                        unsubscribeECG();
                    }
                });

    }

    private void unsubscribeECG() {
        if (mECGSubscription != null) {
            mECGSubscription.unsubscribe();
            mECGSubscription = null;
        }
    }

    private void unsubscribeHR() {
        if (mHRSubscription != null) {
            mHRSubscription.unsubscribe();
            mHRSubscription = null;
        }
    }

    void unsubscribeAll() {
        Log.d(LOG_TAG,"unsubscribeAll()");
        unsubscribeECG();
        unsubscribeHR();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            enableECGSubscription();
        }
        else {
            unsubscribeECG();
        }

    }
}
