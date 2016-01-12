package com.astoev.cave.survey.service.bluetooth;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.Pair;

import com.astoev.cave.survey.Constants;
import com.astoev.cave.survey.R;
import com.astoev.cave.survey.activity.UIUtilities;
import com.astoev.cave.survey.activity.main.BTActivity;
import com.astoev.cave.survey.activity.main.Refresheable;
import com.astoev.cave.survey.exception.DataException;
import com.astoev.cave.survey.service.bluetooth.device.AbstractBluetoothDevice;
import com.astoev.cave.survey.service.bluetooth.device.AbstractBluetoothLEDevice;
import com.astoev.cave.survey.service.bluetooth.device.AbstractBluetoothRFCOMMDevice;
import com.astoev.cave.survey.service.bluetooth.device.ble.LeicaDistoBluetoothLEDevice;
import com.astoev.cave.survey.service.bluetooth.device.comm.CEMILDMBluetoothDevice;
import com.astoev.cave.survey.service.bluetooth.device.comm.DistoXBluetoothDevice;
import com.astoev.cave.survey.service.bluetooth.device.comm.LaserAceBluetoothDevice;
import com.astoev.cave.survey.service.bluetooth.device.comm.TruPulse360BBluetoothDevice;
import com.astoev.cave.survey.util.ConfigUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created with IntelliJ IDEA.
 * User: astoev
 * Date: 10/23/13
 * Time: 10:12 AM
 * To change this template use File | Settings | File Templates.
 */
public class BluetoothService {


    private static final Set<AbstractBluetoothRFCOMMDevice> SUPPORTED_BLUETOOTH_COM_DEVICES = new HashSet<>();
    private static final Set<AbstractBluetoothLEDevice> SUPPORTED_BLUETOOTH_LE_DEVICES = new HashSet<>();

    static {

        // COMM devices
        SUPPORTED_BLUETOOTH_COM_DEVICES.add(new CEMILDMBluetoothDevice());
        SUPPORTED_BLUETOOTH_COM_DEVICES.add(new LaserAceBluetoothDevice());
        SUPPORTED_BLUETOOTH_COM_DEVICES.add(new TruPulse360BBluetoothDevice());
        SUPPORTED_BLUETOOTH_COM_DEVICES.add(new DistoXBluetoothDevice());

        // LE devices
//        SUPPORTED_BLUETOOTH_LE_DEVICES.add(new BoschPLR40CBluetoothLEDevice());
        SUPPORTED_BLUETOOTH_LE_DEVICES.add(new LeicaDistoBluetoothLEDevice());
    }

    // generic
    private static BluetoothDevice mSelectedDevice = null;
    private static AbstractBluetoothDevice mSelectedDeviceSpec = null;
    private static Activity mCurrContext = null;

    // COMM specific
    private static CommDeviceCommunicationThread mCommunicationThread = null;

    // LE specific
    private static BluetoothAdapter.LeScanCallback leCallback = null;
    private static BluetoothGatt mBluetoothGatt = null;
    private static BluetoothDevice mLastLEDevice = null;
    private static MyBluetoothGattCallback leDataCallback = null;
    private static int mLeDeviceState = R.string.bt_state_none;


    public static boolean isBluetoothSupported() {
        return mCurrContext != null && mCurrContext.getSystemService(Context.BLUETOOTH_SERVICE) != null && BluetoothAdapter.getDefaultAdapter() != null;
    }

    public static boolean isBluetoothLESupported() {
        return isBluetoothSupported() && mCurrContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public static boolean isDeviceSelected() {
        return mSelectedDevice != null;
    }

    public static boolean askBluetoothOn(Activity aParentActivity) {
        if (isBluetoothSupported()) {
            return BluetoothAdapter.getDefaultAdapter().isEnabled();
        }
        return false;
    }


    public static void sendReadMeasureCommand(final ResultReceiver receiver, final Constants.Measures aMeasure, final Constants.Measures[] aMeasuresWelcome) {

        // measurements requested
        List<Constants.MeasureTypes> measureTypes = new ArrayList<Constants.MeasureTypes>();
        List<Constants.Measures> measureTargets = new ArrayList<Constants.Measures>();

        measureTypes.add(getMeasureTypeFromTarget(aMeasure));
        measureTargets.add(aMeasure);

        if (aMeasuresWelcome != null) {
            for (Constants.Measures m : aMeasuresWelcome) {
                measureTypes.add(getMeasureTypeFromTarget(m));
                measureTargets.add(m);
            }
        }

        // communication
        if (mCommunicationThread != null) {

            Log.i(Constants.LOG_TAG_BT, "Send read command for " + aMeasure);
            mCommunicationThread.awaitMeasures(measureTypes, measureTargets, receiver);
        } else if (mBluetoothGatt != null) {

            Log.i(Constants.LOG_TAG_BT, "Request LE read " + aMeasure);
            leDataCallback.awaitMeasures(measureTypes, measureTargets, receiver);
        } else {
            Log.d(Constants.LOG_TAG_BT, "Drop BT command : inactive communication : " + aMeasure);
        }
    }

    public static Constants.MeasureTypes getMeasureTypeFromTarget(Constants.Measures aMeasure) {
        switch (aMeasure) {
            case distance:
            case up:
            case down:
            case left:
            case right:
                return Constants.MeasureTypes.distance;

            case angle:
                return Constants.MeasureTypes.angle;

            case slope:
                return Constants.MeasureTypes.slope;

            default:
                return null;
        }
    }

    public static void stop() {
        if (mCommunicationThread != null) {
            mCommunicationThread.cancel();
        }
        if (isBluetoothLESupported()) {
            stopLE();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private static void stopLE() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
        }
    }

    public static synchronized void selectDevice(final String aDeviceAddress) {
        mSelectedDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(aDeviceAddress);
        mSelectedDeviceSpec = getSupportedDevice(mSelectedDevice.getName());

        Log.i(Constants.LOG_TAG_BT, "Selected " + aDeviceAddress + " : " + mSelectedDevice + " of type " + mSelectedDeviceSpec.getDescription());

        if (mSelectedDeviceSpec instanceof AbstractBluetoothRFCOMMDevice) {
            if (mCommunicationThread != null) {
                mCommunicationThread.cancel();
                try {
                    mCommunicationThread.join();
                } catch (InterruptedException e) {
                    Log.e(Constants.LOG_TAG_BT, "Interrupted waiting old thread to complete, e");
                }
            }

            mCommunicationThread = new CommDeviceCommunicationThread(mSelectedDevice, (AbstractBluetoothRFCOMMDevice) mSelectedDeviceSpec);
            mCommunicationThread.start();
        } else {
            // require newer android to work with LE devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // check if we need to connect from scratch or just reconnect to previous device
                if (mBluetoothGatt != null) {
                    Log.i(Constants.LOG_TAG_BT, "Connecting LE");
                    // just reconnect
                    if (mBluetoothGatt.connect()) {
                        updateLeDeviceState(R.string.bt_state_connecting);
                    } else {
                        Log.e(Constants.LOG_TAG_BT, "Failed to connect");
                        UIUtilities.showDeviceDisconnectedNotification(ConfigUtil.getContext(), mSelectedDeviceSpec.getDescription());
                    }
                } else {
                    Log.i(Constants.LOG_TAG_BT, "Re-connecting LE");
                    // connect with remote device
                    leDataCallback = new MyBluetoothGattCallback();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        if (!mSelectedDevice.createBond()) {
                            Log.e(Constants.LOG_TAG_BT, "Failed to create bond");
                            UIUtilities.showDeviceDisconnectedNotification(ConfigUtil.getContext(), mSelectedDeviceSpec.getDescription());
                        }
                    }
                    mBluetoothGatt = mSelectedDevice.connectGatt(mCurrContext, false, leDataCallback);
                    updateLeDeviceState(R.string.bt_state_connecting);
                }
            } else {
                Log.i(Constants.LOG_TAG_BT, "Unsupported version ");
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
    public static BluetoothSocket callSafeCreateInsecureRfcommSocketToServiceRecord(BluetoothDevice deviceaArg) throws IOException {
        return deviceaArg.createInsecureRfcommSocketToServiceRecord(((AbstractBluetoothRFCOMMDevice) mSelectedDeviceSpec).getSPPUUID());
    }

    public static boolean isPaired() {
        return mCommunicationThread != null && mCommunicationThread.ismPaired();
    }

    public static AbstractBluetoothDevice getSupportedDevice(String aDeviceName) {
        for (AbstractBluetoothRFCOMMDevice device : SUPPORTED_BLUETOOTH_COM_DEVICES) {
            if (device.isNameSupported(aDeviceName)) {
                return device;
            }
        }
        for (AbstractBluetoothLEDevice device : SUPPORTED_BLUETOOTH_LE_DEVICES) {
            if (device.isNameSupported(aDeviceName)) {
                return device;
            }
        }
        return null;
    }

    public static boolean isSupported(String aDeviceName) {
        return getSupportedDevice(aDeviceName) != null;
    }

    public static List<AbstractBluetoothDevice> getSupportedDevices() {
        List<AbstractBluetoothDevice> devicesByDescription = new ArrayList(SUPPORTED_BLUETOOTH_COM_DEVICES);
        devicesByDescription.addAll(new ArrayList(SUPPORTED_BLUETOOTH_LE_DEVICES));
        Collections.sort(devicesByDescription);
        return devicesByDescription;
    }

    public static boolean isSupported(BluetoothDevice aDevice) {
        return aDevice != null && isSupported(aDevice.getName());
    }

    public static List<Pair<String, String>> getPairedCompatibleDevices() {
        List<Pair<String, String>> result = new ArrayList<Pair<String, String>>();
        Set<BluetoothDevice> devices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
        for (BluetoothDevice d : devices) {
            if (isSupported(d.getName())) {
                result.add(new Pair<String, String>(d.getName(), d.getAddress()));
            }
        }
        if (mLastLEDevice != null) {
            Pair<String, String> lastLeDevice = new Pair<String, String>(mLastLEDevice.getName(), mLastLEDevice.getAddress());
            if (!devices.contains(lastLeDevice)) {
                result.add(lastLeDevice);
            }
        }
        return result;
    }

    public static String getCurrDeviceStatus() {
        if (mSelectedDevice == null) {
            return mCurrContext.getString(R.string.bt_state_unknown);
        }

        if (mSelectedDeviceSpec instanceof AbstractBluetoothRFCOMMDevice) {
            switch (mSelectedDevice.getBondState()) {
                case BluetoothDevice.BOND_BONDED:
                    return mCurrContext.getString(R.string.bt_state_bonded);

                case BluetoothDevice.BOND_BONDING:
                    return mCurrContext.getString(R.string.bt_state_bonding);

                case BluetoothDevice.BOND_NONE:
                    return mCurrContext.getString(R.string.bt_state_none);

                default:
                    return mCurrContext.getString(R.string.bt_state_unknown);
            }
        } else {
            return mCurrContext.getString(mLeDeviceState);
        }

    }

    public static String getCurrDeviceStatusLabel(Context aContext) {
        StringBuilder statusText = new StringBuilder();
        statusText.append(getCurrDeviceStatus());
        statusText.append(" : ");
        statusText.append(BluetoothService.isPaired() ? aContext.getString(R.string.bt_paired) : aContext.getString(R.string.bt_not_paired));
        return statusText.toString();
    }

    public static void updateLeDeviceState(int aStateLabel) {
        mLeDeviceState = aStateLabel;
        ((Refresheable) mCurrContext).refresh();
    }

    // for the current device
    public static boolean isMeasureSupported(Constants.MeasureTypes aMeasureType) {
        return mSelectedDeviceSpec != null && mSelectedDeviceSpec.isMeasureSupported(aMeasureType);
    }

    public static void registerListeners(BTActivity btActivity) {
        mCurrContext = btActivity;
        if (mCommunicationThread != null) {
            mCommunicationThread.registerListeners(btActivity);
        }
    }

    public static void unregisterListeners(BTActivity btActivity) {
        mCurrContext = btActivity;
        if (mCommunicationThread != null) {
            mCommunicationThread.unregisterListeners(btActivity);
        }
    }


    @TargetApi(18)
    public static void discoverBluetoothLEDevices() {
        Log.i(Constants.LOG_TAG_BT, "Start discovery for Bluetooth LE devices");

        leCallback = getLEScanCallback();
        BluetoothAdapter.getDefaultAdapter().startLeScan(leCallback);

/* todo replace for newer versions with
        final BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btManager.getAdapter().getBluetoothLeScanner().startScan(new ScanCallback(){
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                Log.i(Constants.LOG_TAG_BT, "got " + callbackType + " : " + result.toString());
                System.out.println("re = " + result.getDevice().getName());
            }
        });
*/
    }

    @TargetApi(18)
    public static void stopDiscoverBluetoothLEDevices() {
        Log.i(Constants.LOG_TAG_BT, "Stop discovery for Bluetooth LE devices");

        BluetoothAdapter.getDefaultAdapter().stopLeScan(leCallback);
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    public static BluetoothAdapter.LeScanCallback getLEScanCallback()  {

        return new BluetoothAdapter.LeScanCallback() {

            @Override
            public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

                AbstractBluetoothLEDevice deviceSpec = (AbstractBluetoothLEDevice) BluetoothService.getSupportedDevice(device.getName());
                if (deviceSpec != null) {
                    Log.i(Constants.LOG_TAG_BT, "Discovered LE device " + rssi + " : " + device.getName());

                    mLastLEDevice = device;
                    ((Refresheable) mCurrContext).refresh();
                } else {
                    Log.i(Constants.LOG_TAG_BT, "Discovered unsupported LE device " + rssi + " : " + device.getName());
                }
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    static class MyBluetoothGattCallback extends BluetoothGattCallback {

        private List<Constants.MeasureTypes> mMeasureTypes = null;
        private List<Constants.Measures> mTargets = null;
        private ResultReceiver mReceiver = null;

        public void awaitMeasures(List<Constants.MeasureTypes> aMeasureTypes, List<Constants.Measures> aTargets, ResultReceiver aReceiver) {
            // persist expected measurements
            mTargets = aTargets;
            mMeasureTypes = aMeasureTypes;
            mReceiver = aReceiver;

        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(Constants.LOG_TAG_BT, "Connected with " + gatt.getDevice().getName());


                mBluetoothGatt.readRemoteRssi();

                mBluetoothGatt.discoverServices();

                updateLeDeviceState(R.string.bt_state_connecting);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(Constants.LOG_TAG_BT, "LE device disconnected");
                UIUtilities.showDeviceDisconnectedNotification(ConfigUtil.getContext(), mSelectedDeviceSpec.getDescription());

                updateLeDeviceState(R.string.bt_state_none);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // notify connected
                Log.i(Constants.LOG_TAG_BT, "Got services");
                UIUtilities.showDeviceConnectedNotification(ConfigUtil.getContext(), mSelectedDeviceSpec.getDescription());

                // instruct characteristics to notify on change
                AbstractBluetoothLEDevice leDevice = (AbstractBluetoothLEDevice) mSelectedDeviceSpec;
                for (BluetoothGattService service : mBluetoothGatt.getServices()) {
                    Log.d(Constants.LOG_TAG_BT, "Service " + service.getUuid().toString() + " " + service.getType());
                    if (!leDevice.getServices().contains(service.getUuid())) {
                        continue;
                    }

                    for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                        if (!leDevice.getCharacteristics().contains(c.getUuid())) {
                            continue;
                        }

                        Log.d(Constants.LOG_TAG_BT, "Enable notification for: " + c.getUuid().toString());
                        boolean flag = gatt.setCharacteristicNotification(c, true);
                        Log.i(Constants.LOG_TAG_BT, "Notification success: " + flag);


                        for (BluetoothGattDescriptor descriptor : c.getDescriptors()) {
                            if (!leDevice.getDescriptors().contains(descriptor.getUuid())) {
                                continue;
                            }

                            Log.d(Constants.LOG_TAG_BT, "Enable indication for: " + descriptor.getUuid().toString());
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                            flag = gatt.writeDescriptor(descriptor);
                            Log.i(Constants.LOG_TAG_BT, "Indication success " + flag);
                        }

                    }
                }

                stopDiscoverBluetoothLEDevices();
                updateLeDeviceState(R.string.bt_state_connected);
            } else {
                Log.i(Constants.LOG_TAG_BT, "Got services error, retry in a while");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Log.e(Constants.LOG_TAG_BT, "Interrupted: ", e);
                }
                gatt.discoverServices();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.i(Constants.LOG_TAG_BT, "onCharacteristicChanged " + characteristic.getUuid());

            try {
                Log.d(Constants.LOG_TAG_BT, "processing " + characteristic.getUuid());
                // decode
                Measure measure = ((AbstractBluetoothLEDevice) mSelectedDeviceSpec).characteristicToMeasure(characteristic, mMeasureTypes);

                // consume
                sendMeasureToUI(measure);
            } catch (DataException e) {
                Log.e(Constants.LOG_TAG_BT, "Fail to read data: ", e);
                UIUtilities.showNotification(e.getMessage());
            }
        }

        private void sendMeasureToUI(Measure aMeasure) {
            if (aMeasure != null) {
                // consume
                Bundle b = new Bundle();
                b.putFloatArray(Constants.MEASURE_VALUE_KEY,  new float[] {aMeasure.getValue()});
                b.putStringArray(Constants.MEASURE_TYPE_KEY, new String[] {aMeasure.getMeasureType().toString()});
                b.putStringArray(Constants.MEASURE_UNIT_KEY, new String[]{aMeasure.getMeasureUnit().toString()});
                b.putStringArray(Constants.MEASURE_TARGET_KEY, new String[] {mTargets.get(mMeasureTypes.indexOf(aMeasure.getMeasureType())).toString()});
                mReceiver.send(Activity.RESULT_OK, b);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(Constants.LOG_TAG_BT, "RemoteRssi " + rssi);
            }
        }

    }
}