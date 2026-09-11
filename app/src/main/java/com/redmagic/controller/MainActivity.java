package com.redmagic.controller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int LED_COUNT = 16;
    private static final int REQ_PERMISSIONS = 1001;
    
    // UUIDs for characteristics
    private static final UUID UUID_1011 = uuid16(4113);
    private static final UUID UUID_1012 = uuid16(4114);
    private static final UUID UUID_1013 = uuid16(4115);
    private static final UUID UUID_1017 = uuid16(4119);
    private static final UUID UUID_1018 = uuid16(4120);

    // BLE components
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    
    // Characteristics
    private BluetoothGattCharacteristic c1011;
    private BluetoothGattCharacteristic c1012;
    private BluetoothGattCharacteristic c1013;
    private BluetoothGattCharacteristic c1017;
    private BluetoothGattCharacteristic c1018;
    
    // UI elements
    private TextView statusText;
    private TextView fanLevelText;
    private Slider fanLevelSlider;
    private TextView selectedLedText;
    private GridLayout ledGrid;
    private Button[] ledButtons;
    private Slider redSlider, greenSlider, blueSlider;
    private TextView redValueText, greenValueText, blueValueText;
    
    // State
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Set<String> seenDevices = new HashSet<>();
    private final Queue<WriteOp> writeQueue = new ArrayDeque<>();
    private WriteOp activeWrite;
    private boolean scanning = false;
    private boolean writing = false;
    private int selectedLed = 0;
    private int fanLevel = 1;
    private int red = 32, green = 0, blue = 0;
    
    private static class WriteOp {
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;
        final String label;
        
        WriteOp(BluetoothGattCharacteristic characteristic, byte[] data, String label) {
            this.characteristic = characteristic;
            this.data = data;
            this.label = label;
        }
    }
    
    private static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.US, 
            "0000%04x-0000-1000-8000-00805f9b34fb", value & 0xFFFF));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        if (adapter != null) {
            scanner = adapter.getBluetoothLeScanner();
        }
        
        initViews();
        setupListeners();
        requestNeededPermissions();
    }
    
    private void initViews() {
        statusText = findViewById(R.id.statusText);
        fanLevelText = findViewById(R.id.fanLevelText);
        fanLevelSlider = findViewById(R.id.fanLevelSlider);
        selectedLedText = findViewById(R.id.selectedLedText);
        ledGrid = findViewById(R.id.ledGrid);
        redSlider = findViewById(R.id.redSlider);
        greenSlider = findViewById(R.id.greenSlider);
        blueSlider = findViewById(R.id.blueSlider);
        redValueText = findViewById(R.id.redValueText);
        greenValueText = findViewById(R.id.greenValueText);
        blueValueText = findViewById(R.id.blueValueText);
        
        // Create LED buttons
        ledButtons = new Button[LED_COUNT];
        int buttonSize = dp(64);
        int margin = dp(4);
        
        for (int i = 0; i < LED_COUNT; i++) {
            final int idx = i;
            MaterialButton btn = new MaterialButton(this);
            btn.setText(String.valueOf(i + 1));
            btn.setTextSize(14);
            
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = buttonSize;
            params.rowSpec = GridLayout.spec(i / 4, 1f);
            params.columnSpec = GridLayout.spec(i % 4, 1f);
            params.setMargins(margin, margin, margin, margin);
            
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> selectLed(idx));
            
            ledButtons[i] = btn;
            ledGrid.addView(btn);
        }
        
        updateLedSelection();
    }
    
    private void setupListeners() {
        findViewById(R.id.scanButton).setOnClickListener(v -> startScan());
        findViewById(R.id.disconnectButton).setOnClickListener(v -> disconnectGatt());
        
        fanLevelSlider.addOnChangeListener((slider, value, fromUser) -> {
            fanLevel = (int) value;
            updateFanLabel();
        });
        
        findViewById(R.id.applyFanButton).setOnClickListener(v -> setManualFanLevel(fanLevel));
        findViewById(R.id.smartModeButton).setOnClickListener(v -> setSmartMode());
        findViewById(R.id.boostModeButton).setOnClickListener(v -> setBoostMode());
        findViewById(R.id.powerOffButton).setOnClickListener(v -> powerOff());
        
        redSlider.addOnChangeListener((slider, value, fromUser) -> {
            red = (int) value;
            redValueText.setText("R: " + red);
        });
        
        greenSlider.addOnChangeListener((slider, value, fromUser) -> {
            green = (int) value;
            greenValueText.setText("G: " + green);
        });
        
        blueSlider.addOnChangeListener((slider, value, fromUser) -> {
            blue = (int) value;
            blueValueText.setText("B: " + blue);
        });
        
        findViewById(R.id.sendRgbButton).setOnClickListener(v -> sendPerPixelColor(selectedLed, red, green, blue));
        findViewById(R.id.allOffButton).setOnClickListener(v -> allOff());
    }
    
    private void selectLed(int idx) {
        selectedLed = idx;
        updateLedSelection();
        selectedLedText.setText("Selected LED: " + (idx + 1) + " / 16");
    }
    
    private void updateLedSelection() {
        for (int i = 0; i < LED_COUNT; i++) {
            if (i == selectedLed) {
                ledButtons[i].setBackgroundColor(Color.rgb(229, 57, 53));
                ledButtons[i].setTextColor(Color.WHITE);
            } else {
                ledButtons[i].setBackgroundColor(Color.rgb(238, 238, 238));
                ledButtons[i].setTextColor(Color.rgb(33, 33, 33));
            }
        }
    }
    
    private void updateFanLabel() {
        int hex = 0x28 + 4 * (fanLevel - 1);
        fanLevelText.setText(String.format(Locale.US, "Level: %d / 10 (0x%02X)", fanLevel, hex));
    }
    
    // Fan control methods
    private void setManualFanLevel(int level) {
        if (!ensureFanReady()) return;
        
        int hex = 0x28 + 4 * (level - 1);
        queueWrite(c1011, new byte[]{0x02}, "1011=02");
        queueWrite(c1018, new byte[]{0x00}, "1018=00");
        queueWrite(c1017, new byte[]{0x00}, "1017=00");
        queueWrite(c1012, new byte[]{(byte) hex}, String.format(Locale.US, "1012=%02X", hex));
        pumpWrites();
        toast("Setting manual fan level " + level);
    }
    
    private void setSmartMode() {
        if (!ensureFanReady()) return;
        
        queueWrite(c1011, new byte[]{0x02}, "1011=02");
        queueWrite(c1017, new byte[]{0x00}, "1017=00");
        queueWrite(c1018, new byte[]{0x01}, "1018=01");
        pumpWrites();
        toast("Setting smart mode");
    }
    
    private void setBoostMode() {
        if (!ensureFanReady()) return;
        
        queueWrite(c1011, new byte[]{0x02}, "1011=02");
        queueWrite(c1018, new byte[]{0x00}, "1018=00");
        queueWrite(c1012, new byte[]{0x50}, "1012=50");
        queueWrite(c1017, new byte[]{0x01}, "1017=01");
        pumpWrites();
        toast("Setting boost mode");
    }
    
    private void powerOff() {
        if (!ensureFanReady()) return;
        
        queueWrite(c1011, new byte[]{0x02}, "1011=02");
        queueWrite(c1018, new byte[]{0x00}, "1018=00");
        queueWrite(c1017, new byte[]{0x00}, "1017=00");
        queueWrite(c1012, new byte[]{0x00}, "1012=00");
        pumpWrites();
        toast("Powering off");
    }
    
    // Light control methods
    private void sendPerPixelColor(int ledIdx, int r, int g, int b) {
        if (!ensureLightReady()) return;
        
        byte ledByte = (byte) (ledIdx & 0xFF);
        byte[] f0 = new byte[]{(byte) 0xF0, ledByte, (byte) (r & 0xFF), (byte) (g & 0xFF)};
        byte[] f1 = new byte[]{(byte) 0xF1, ledByte, (byte) (b & 0xFF), 0x00};
        
        queueWrite(c1013, f0, String.format("F0[%d]RGB(%d,%d,%d)", ledIdx, r, g, b));
        queueWrite(c1013, f1, String.format("F1[%d]B(%d)", ledIdx, b));
        pumpWrites();
        toast(String.format("Sent RGB(%d,%d,%d) to LED %d", r, g, b, ledIdx + 1));
    }
    
    private void allOff() {
        if (!ensureLightReady()) return;
        
        for (int i = 0; i < LED_COUNT; i++) {
            byte ledByte = (byte) (i & 0xFF);
            queueWrite(c1013, new byte[]{(byte) 0xF0, ledByte, 0x00, 0x00}, "F0[" + i + "]");
            queueWrite(c1013, new byte[]{(byte) 0xF1, ledByte, 0x00, 0x00}, "F1[" + i + "]");
        }
        pumpWrites();
        toast("Turning all LEDs off");
    }
    
    private boolean ensureFanReady() {
        if (gatt == null || c1011 == null || c1012 == null || c1017 == null || c1018 == null) {
            toast("Not connected to fan characteristics");
            return false;
        }
        return true;
    }
    
    private boolean ensureLightReady() {
        if (gatt == null || c1013 == null) {
            toast("Not connected to light characteristic");
            return false;
        }
        return true;
    }
    
    // Write queue management
    private void queueWrite(BluetoothGattCharacteristic characteristic, byte[] data, String label) {
        synchronized (writeQueue) {
            writeQueue.offer(new WriteOp(characteristic, data, label));
        }
    }
    
    private void pumpWrites() {
        if (writing || gatt == null) return;
        
        WriteOp next;
        synchronized (writeQueue) {
            next = writeQueue.poll();
        }
        
        if (next == null) return;
        
        BluetoothGattCharacteristic c = next.characteristic;
        int props = c.getProperties();
        int writeType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        
        writing = true;
        activeWrite = next;
        
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = gatt.writeCharacteristic(c, next.data, writeType);
            started = (result == BluetoothGatt.GATT_SUCCESS);
        } else {
            c.setWriteType(writeType);
            c.setValue(next.data);
            started = gatt.writeCharacteristic(c);
        }
        
        if (!started) {
            writing = false;
            activeWrite = null;
            setStatus("Write start failed: " + next.label);
            mainHandler.postDelayed(this::pumpWrites, 80);
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            mainHandler.postDelayed(() -> {
                writing = false;
                activeWrite = null;
                pumpWrites();
            }, 45);
        }
    }
    
    // BLE scanning
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String key = device.getAddress();
            
            if (seenDevices.add(key)) {
                devices.add(device);
                mainHandler.post(() -> 
                    setStatus("Found " + devices.size() + " BLE devices…"));
            }
        }
        
        @Override
        public void onScanFailed(int errorCode) {
            mainHandler.post(() -> 
                setStatus("Scan failed: " + errorCode));
        }
    };
    
    private void startScan() {
        if (!hasPermissions()) {
            requestNeededPermissions();
            return;
        }
        
        if (adapter == null || !adapter.isEnabled()) {
            toast("Please enable Bluetooth");
            return;
        }
        
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            toast("BLE scanner unavailable");
            return;
        }
        
        devices.clear();
        seenDevices.clear();
        scanning = true;
        setStatus("Scanning for BLE devices…");
        
        try {
            scanner.startScan(scanCallback);
            mainHandler.postDelayed(() -> {
                stopScan();
                showDevices();
            }, 6000);
        } catch (SecurityException e) {
            toast("Permission denied");
        }
    }
    
    private void stopScan() {
        if (scanning && scanner != null && hasPermissions()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
            }
        }
        scanning = false;
    }
    
    private void showDevices() {
        if (devices.isEmpty()) {
            setStatus("No devices found");
            return;
        }
        
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name = safeName(d);
            labels[i] = (name == null || name.isEmpty() ? "Unnamed BLE" : name) + "\n" + d.getAddress();
        }
        
        new AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(labels, (dialog, which) -> connect(devices.get(which)))
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private String safeName(BluetoothDevice device) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }
    
    private void connect(BluetoothDevice device) {
        disconnectGatt();
        
        String name = safeName(device);
        setStatus("Connecting to " + (name != null ? name : device.getAddress()) + "…");
        
        if (hasPermissions()) {
            try {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                setStatus("Connection permission denied");
            }
        }
    }
    
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> setStatus("Connected, discovering services…"));
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    mainHandler.post(() -> setStatus("Service discovery permission denied"));
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                clearCharacteristics();
                writing = false;
                activeWrite = null;
                synchronized (writeQueue) {
                    writeQueue.clear();
                }
                mainHandler.post(() -> setStatus("Disconnected"));
            }
        }
        
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post(() -> setStatus("Service discovery failed: " + status));
                return;
            }
            
            clearCharacteristics();
            
            for (BluetoothGattService service : g.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (isWritable(c)) {
                        UUID u = c.getUuid();
                        if (matches16(u, 4113)) c1011 = c;
                        else if (matches16(u, 4114)) c1012 = c;
                        else if (matches16(u, 4115)) c1013 = c;
                        else if (matches16(u, 4119)) c1017 = c;
                        else if (matches16(u, 4120)) c1018 = c;
                    }
                }
            }
            
            int count = 0;
            if (c1011 != null) count++;
            if (c1012 != null) count++;
            if (c1013 != null) count++;
            if (c1017 != null) count++;
            if (c1018 != null) count++;
            
            final int foundCount = count;
            mainHandler.post(() -> setStatus(String.format("Ready: Found %d/5 characteristics", foundCount)));
        }
        
        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            WriteOp op = activeWrite;
            if (op != null && c.getUuid().equals(op.characteristic.getUuid())) {
                writing = false;
                activeWrite = null;
                
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    mainHandler.post(() -> setStatus(op.label + " write failed: " + status));
                }
                
                mainHandler.post(MainActivity.this::pumpWrites);
            }
        }
    };
    
    private static boolean isWritable(BluetoothGattCharacteristic c) {
        int props = c.getProperties();
        return (props & (BluetoothGattCharacteristic.PROPERTY_WRITE | 
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }
    
    private static boolean matches16(UUID uuid, int shortUuid) {
        String s = uuid.toString().toLowerCase(Locale.US);
        String needle = String.format(Locale.US, "0000%04x-", shortUuid & 0xFFFF);
        return s.startsWith(needle);
    }
    
    private void clearCharacteristics() {
        c1011 = c1012 = c1013 = c1017 = c1018 = null;
    }
    
    private void disconnectGatt() {
        stopScan();
        
        BluetoothGatt old = gatt;
        gatt = null;
        clearCharacteristics();
        writing = false;
        activeWrite = null;
        
        synchronized (writeQueue) {
            writeQueue.clear();
        }
        
        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (SecurityException ignored) {
            }
        }
        
        setStatus("Disconnected");
    }
    
    // Permissions
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            }, REQ_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION
            }, REQ_PERMISSIONS);
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS && !hasPermissions()) {
            toast("Bluetooth permissions required");
        }
    }
    
    @Override
    protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
    
    // Utilities
    private void setStatus(String s) {
        if (statusText != null) {
            statusText.setText(s);
        }
    }
    
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
    
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
