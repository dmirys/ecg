package com.yastrebkov.ecg;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
//import android.widget.TextView;

import com.movesense.mds.Mds;
import com.movesense.mds.MdsConnectionListener;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.scan.ScanSettings;

import java.util.ArrayList;

import rx.Subscription;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener  {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_LOCATION = 1;

    private boolean is_scanning_ = false;

    static Mds mMds;
    static private RxBleClient mBleClient;

    private ListView mScanResultListView;
    private static ArrayList<DeviceSearchList> mScanResArrayList = new ArrayList<>();
    ArrayAdapter<DeviceSearchList> mScanResArrayAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init Scan UI
        SetSearchClickHandler();
        mScanResultListView = (ListView)findViewById(R.id.device_list);
        mScanResArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, mScanResArrayList);
        mScanResultListView.setAdapter(mScanResArrayAdapter);
        mScanResultListView.setOnItemLongClickListener(this);
        mScanResultListView.setOnItemClickListener(this);

        // Make sure we have all the permissions this app needs
        requestNeededPermissions();

        initMds();
    }

    private RxBleClient getBleClient() {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(this);
        }

        return mBleClient;
    }

    private void initMds() {
        if (mMds == null) {
            mMds = Mds.builder().build(this);
        }
    }

    void requestNeededPermissions()
    {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    MY_PERMISSIONS_REQUEST_LOCATION);

        }
    }

    Subscription mScanSubscription;

    private void StartSearch() {
        is_scanning_ = true;

        ((Button)findViewById(R.id.search_btn)).setText("Остановить поиск");

        // Start with empty list
        mScanResArrayList.clear();
        mScanResArrayAdapter.notifyDataSetChanged();

        mScanSubscription = getBleClient().scanBleDevices(
                new ScanSettings.Builder()
                        // .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // change if needed
                        // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // change if needed
                        .build()
                // add filters if needed
        )
                .subscribe(
                        scanResult -> {
                            Log.d(LOG_TAG,"scanResult: " + scanResult);

                            // Process scan result here. filter movesense devices.
                            if (scanResult.getBleDevice()!=null &&
                                    scanResult.getBleDevice().getName() != null &&
                                    scanResult.getBleDevice().getName().startsWith("Movesense")) {

                                // replace if exists already, add otherwise
                                DeviceSearchList msr = new DeviceSearchList(scanResult);
                                if (mScanResArrayList.contains(msr))
                                    mScanResArrayList.set(mScanResArrayList.indexOf(msr), msr);
                                else
                                    mScanResArrayList.add(0, msr);

                                mScanResArrayAdapter.notifyDataSetChanged();
                            }
                        },
                        throwable -> {
                            Log.e(LOG_TAG,"scan error: " + throwable);
                            // Handle an error here.

                            // Re-enable scan buttons, just like with ScanStop
                            StopSearch();
                        }
                );
    }

    private void StopSearch() {
        is_scanning_ = false;

        if (mScanSubscription != null)
        {
            mScanSubscription.unsubscribe();
            mScanSubscription = null;
        }

        ((Button)findViewById(R.id.search_btn)).setText("Повторить поиск");
    }

    private void SetSearchClickHandler() {
        ((Button)findViewById(R.id.search_btn)).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (is_scanning_) {
                            StopSearch();
                        } else {
                            StartSearch();
                        }
                    }
                }
        );
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return;

        DeviceSearchList device = mScanResArrayList.get(position);
        if (!device.isConnected()) {
            // Stop scanning
            StopSearch();

            // And connect to the device
            connectBLEDevice(device);
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return false;

        DeviceSearchList device = mScanResArrayList.get(position);

        Log.i(LOG_TAG, "Disconnecting from BLE device: " + device.macAddress);
        mMds.disconnect(device.macAddress);

        return true;
    }

    private void connectBLEDevice(DeviceSearchList device) {
        RxBleDevice bleDevice = getBleClient().getBleDevice(device.macAddress);
        final Activity me = this;
        Log.i(LOG_TAG, "Connecting to BLE device: " + bleDevice.getMacAddress());
        mMds.connect(bleDevice.getMacAddress(), new MdsConnectionListener() {

            @Override
            public void onConnect(String s) {
                Log.d(LOG_TAG, "onConnect:" + s);
            }

            @Override
            public void onConnectionComplete(String macAddress, String serial) {
                for (DeviceSearchList sr : mScanResArrayList) {
                    if (sr.macAddress.equalsIgnoreCase(macAddress)) {
                        sr.markConnected(serial);
                        break;
                    }
                }
                mScanResArrayAdapter.notifyDataSetChanged();

                // Open the ECGActivity
                Intent intent = new Intent(me, EcgActivity.class);
                intent.putExtra(EcgActivity.SERIAL, serial);
                startActivity(intent);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "onError:" + e);

                showConnectionError(e);
            }

            @Override
            public void onDisconnect(String bleAddress) {
                Log.d(LOG_TAG, "onDisconnect: " + bleAddress);
                for (DeviceSearchList sr : mScanResArrayList) {
                    if (bleAddress.equals(sr.macAddress)) {
                        // Unsubscribe all from possible
                        if (sr.connectedSerial != null &&
                                EcgActivity.s_INSTANCE != null &&
                                sr.connectedSerial.equals(EcgActivity.s_INSTANCE.connectedSerial)) {
                            EcgActivity.s_INSTANCE.unsubscribeAll();
                            EcgActivity.s_INSTANCE.finish();
                        }
                        sr.markDisconnected();
                    }
                }
                mScanResArrayAdapter.notifyDataSetChanged();
            }
        });
    }

    private void showConnectionError(MdsException e) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Connection Error:")
                .setMessage(e.getMessage());

        builder.create().show();
    }
}
